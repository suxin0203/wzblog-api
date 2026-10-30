package com.wzblog.modules.points.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.modules.points.api.vo.PointsAccountVO;
import com.wzblog.modules.points.api.vo.PointsLedgerVO;
import com.wzblog.modules.points.api.vo.TitleItemVO;
import com.wzblog.modules.points.api.vo.TitleVO;
import com.wzblog.modules.points.domain.LedgerEntry;
import com.wzblog.modules.points.domain.PointsAccount;
import com.wzblog.modules.points.domain.PointsType;
import com.wzblog.modules.points.domain.event.PointsAdjustedEvent;
import com.wzblog.modules.points.infra.mapper.PointsAccountMapper;
import com.wzblog.modules.points.infra.mapper.PointsLedgerMapper;
import com.wzblog.modules.identity.app.UserQueryService;
import com.wzblog.modules.points.infra.mapper.TitleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 积分读取（账户/流水）与管理员调分。
 */
@Service
public class PointsQueryService {

    private static final Logger log = LoggerFactory.getLogger(PointsQueryService.class);
    private static final String ADJUST_IDEM_PREFIX = "points:adjust:idem:";
    private static final String PROCESSING = "PROCESSING";
    private static final String SUCCESS_PREFIX = "SUCCESS:";
    private static final Duration ADJUST_PROCESSING_TTL = Duration.ofSeconds(60);
    private static final Duration ADJUST_SUCCESS_TTL = Duration.ofHours(24);
    private static final DefaultRedisScript<Long> RELEASE_PROCESSING = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final TitleMapper titleMapper;
    private final com.wzblog.modules.identity.app.UserQueryService userQueryService;
    private final PointsAppService pointsAppService;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;
    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;

    public PointsQueryService(PointsAccountMapper accountMapper,
                              PointsLedgerMapper ledgerMapper,
                              TitleMapper titleMapper,
                              UserQueryService userQueryService,
                              PointsAppService pointsAppService,
                              AuditRecorder auditRecorder,
                              ApplicationEventPublisher eventPublisher,
                              StringRedisTemplate redis,
                              ObjectMapper objectMapper) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.titleMapper = titleMapper;
        this.userQueryService = userQueryService;
        this.pointsAppService = pointsAppService;
        this.auditRecorder = auditRecorder;
        this.eventPublisher = eventPublisher;
        this.redis = redis;
        this.objectMapper = objectMapper;
    }

    /** 账户快照：无行返回零值 VO，不落库（TC-M4-001）。 */
    @Transactional(readOnly = true)
    public PointsAccountVO account(CurrentUser user) {
        PointsAccount account = accountMapper.selectByUserId(user.id());
        Long activeTitleId = userQueryService.getActiveTitleId(user.id());
        List<TitleItemVO> titles = titleMapper.selectUserTitles(user.id(), activeTitleId).stream()
                .map(row -> new TitleItemVO(String.valueOf(row.id()), row.name(), row.cssClass(),
                        Times.toInstant(row.acquiredAt()), row.active()))
                .toList();
        TitleItemVO activeTitle = titles.stream().filter(TitleItemVO::active).findFirst().orElse(null);
        return new PointsAccountVO(String.valueOf(user.id()),
                account == null ? 0 : account.getBalance(),
                account == null ? 0 : account.getTotalEarned(),
                account == null ? 0 : account.getTotalSpent(),
                Times.toInstant(account == null ? null : account.getUpdatedAt()),
                titles, activeTitle);
    }

    @Transactional(readOnly = true)
    public PageResult<PointsLedgerVO> myLedger(CurrentUser user, PointsType type, int page,
                                               int pageSize) {
        String typeParam = type == null ? null : type.name();
        long total = ledgerMapper.countUserPage(user.id(), typeParam);
        int offset = (page - 1) * pageSize;
        List<PointsLedgerVO> list = ledgerMapper
                .selectUserPage(user.id(), typeParam, pageSize, offset)
                .stream().map(this::toVo).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    @Transactional(readOnly = true)
    public PageResult<PointsLedgerVO> adminLedger(Long userId, PointsType type, String bizType,
                                                  LocalDateTime from, LocalDateTime to, int page,
                                                  int pageSize) {
        long total = ledgerMapper.countAdminPage(userId, type == null ? null : type.name(),
                bizType, from, to);
        int offset = (page - 1) * pageSize;
        List<PointsLedgerVO> list = ledgerMapper
                .selectAdminPage(userId, type == null ? null : type.name(), bizType, from, to,
                        pageSize, offset)
                .stream().map(this::toVo).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    /** 管理员调分：Redis 三态幂等（PROCESSING 60s / SUCCESS 24h），失败必释放。 */
    @Transactional
    public PointsLedgerVO adminAdjust(CurrentUser operator, Long userId, int amount, String reason,
                                      String idempotencyKey) {
        String clientKey = requireIdempotencyKey(idempotencyKey);
        String redisKey = ADJUST_IDEM_PREFIX + operator.id() + ":" + clientKey;

        String cached = redis.opsForValue().get(redisKey);
        if (cached != null) {
            return replayAdjust(cached, redisKey);
        }
        Boolean acquired = redis.opsForValue().setIfAbsent(redisKey, PROCESSING, ADJUST_PROCESSING_TTL);
        if (!Boolean.TRUE.equals(acquired)) {
            cached = redis.opsForValue().get(redisKey);
            if (cached != null) {
                return replayAdjust(cached, redisKey);
            }
            throw new BusinessException(ErrorCode.POINTS_ADJUST_IN_PROGRESS);
        }

        AtomicReference<PointsLedgerVO> snapshot = new AtomicReference<>();
        boolean syncActive = TransactionSynchronizationManager.isSynchronizationActive();
        if (syncActive) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    storeAdjustSuccess(redisKey, snapshot.get());
                }

                @Override
                public void afterCompletion(int status) {
                    if (status != TransactionSynchronization.STATUS_COMMITTED) {
                        releaseAdjustProcessing(redisKey);
                    }
                }
            });
        }

        try {
            if (amount == 0 || Math.abs(amount) > 100_000) {
                throw new BusinessException(ErrorCode.POINTS_ADJUST_INVALID);
            }
            String ledgerKey = "admin-adjust:" + operator.id() + ":" + clientKey;
            var result = pointsAppService.post(userId, PointsType.ADMIN_ADJUST, amount, "SYSTEM", null,
                    ledgerKey, reason, operator.id());
            LedgerEntry entry = ledgerMapper.selectByIdempotencyKey(ledgerKey);
            if (entry == null) {
                throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR, "调分流水写入失败");
            }
            // 数据库唯一键兜底命中（如 Redis 快照丢失）时不再重复审计/通知
            if (!result.duplicate()) {
                auditRecorder.record(operator, "POINTS_ADJUST", "USER", String.valueOf(userId),
                        null, "{\"amount\":" + amount + ",\"reason\":\"" + safe(reason) + "\"}");
                eventPublisher.publishEvent(new PointsAdjustedEvent(result.ledgerId(), userId, amount,
                        result.balanceAfter(), reason));
            }
            PointsLedgerVO vo = toVo(entry);
            snapshot.set(vo);
            if (!syncActive) {
                storeAdjustSuccess(redisKey, vo);
            }
            return vo;
        } catch (RuntimeException ex) {
            releaseAdjustProcessing(redisKey);
            throw ex;
        }
    }

    private String requireIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "缺少幂等键", null);
        }
        String key = idempotencyKey.trim();
        if (key.length() > 80) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "幂等键过长", null);
        }
        return key;
    }

    private PointsLedgerVO replayAdjust(String cached, String redisKey) {
        if (cached != null && cached.startsWith(SUCCESS_PREFIX)) {
            try {
                return objectMapper.readValue(cached.substring(SUCCESS_PREFIX.length()),
                        PointsLedgerVO.class);
            } catch (Exception ex) {
                log.warn("调分幂等快照解析失败 key={}", redisKey, ex);
                redis.delete(redisKey);
                throw new BusinessException(ErrorCode.COMMON_INTERNAL_ERROR);
            }
        }
        throw new BusinessException(ErrorCode.POINTS_ADJUST_IN_PROGRESS);
    }

    private void storeAdjustSuccess(String redisKey, PointsLedgerVO vo) {
        if (vo == null) {
            return;
        }
        try {
            redis.opsForValue().set(redisKey, SUCCESS_PREFIX + objectMapper.writeValueAsString(vo),
                    ADJUST_SUCCESS_TTL);
        } catch (Exception ex) {
            log.warn("调分幂等快照写入失败 key={}", redisKey, ex);
        }
    }

    private void releaseAdjustProcessing(String redisKey) {
        try {
            redis.execute(RELEASE_PROCESSING, List.of(redisKey), PROCESSING);
        } catch (Exception ex) {
            log.warn("调分幂等键释放失败 key={}", redisKey, ex);
        }
    }

    /** M7 看板：区间发放/消耗积分合计（正/负流水绝对值），[0]=issued [1]=consumed。 */
    @Transactional(readOnly = true)
    public long[] sumIssuedAndConsumedBetween(java.time.LocalDateTime from,
                                              java.time.LocalDateTime to) {
        java.util.Map<String, Object> row = ledgerMapper.sumIssuedAndConsumedBetween(from, to);
        if (row == null) {
            return new long[]{0, 0};
        }
        return new long[]{toLong(row.get("issued")), toLong(row.get("consumed"))};
    }

    private static long toLong(Object value) {
        return value == null ? 0 : Long.parseLong(String.valueOf(value));
    }

    public List<TitleVO> activeTitles() {
        return titleMapper.selectActive().stream()
                .map(title -> new TitleVO(String.valueOf(title.getId()), title.getName(),
                        title.getCssClass(), title.getDescription(), title.getSortOrder()))
                .toList();
    }

    private PointsLedgerVO toVo(LedgerEntry entry) {
        return new PointsLedgerVO(String.valueOf(entry.getId()), entry.getAmount(),
                entry.getBalanceAfter(), entry.getType(), entry.getBizType(), entry.getBizId(),
                entry.getRemark(), Times.toInstant(entry.getCreatedAt()));
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
