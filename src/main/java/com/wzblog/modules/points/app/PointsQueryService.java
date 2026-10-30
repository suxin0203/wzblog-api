package com.wzblog.modules.points.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 积分读取（账户/流水）与管理员调分。
 */
@Service
public class PointsQueryService {

    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final TitleMapper titleMapper;
    private final com.wzblog.modules.identity.app.UserQueryService userQueryService;
    private final PointsAppService pointsAppService;
    private final AuditRecorder auditRecorder;
    private final ApplicationEventPublisher eventPublisher;

    public PointsQueryService(PointsAccountMapper accountMapper,
                              PointsLedgerMapper ledgerMapper,
                              TitleMapper titleMapper,
                              UserQueryService userQueryService,
                              PointsAppService pointsAppService,
                              AuditRecorder auditRecorder,
                              ApplicationEventPublisher eventPublisher) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.titleMapper = titleMapper;
        this.userQueryService = userQueryService;
        this.pointsAppService = pointsAppService;
        this.auditRecorder = auditRecorder;
        this.eventPublisher = eventPublisher;
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

    /** 管理员调分：必写流水 + 审计 + 事件。 */
    @Transactional
    public PointsLedgerVO adminAdjust(CurrentUser operator, Long userId, int amount, String reason) {
        if (amount == 0 || Math.abs(amount) > 100_000) {
            throw new BusinessException(ErrorCode.POINTS_ADJUST_INVALID);
        }
        String idempotencyKey = "admin-adjust:" + java.util.UUID.randomUUID()
                .toString().replace("-", "");
        var result = pointsAppService.post(userId, PointsType.ADMIN_ADJUST, amount, "SYSTEM", null,
                idempotencyKey, reason, operator.id());
        auditRecorder.record(operator, "POINTS_ADJUST", "USER", String.valueOf(userId),
                null, "{\"amount\":" + amount + ",\"reason\":\"" + safe(reason) + "\"}");
        eventPublisher.publishEvent(new PointsAdjustedEvent(result.ledgerId(), userId, amount,
                result.balanceAfter(), reason));
        LedgerEntry entry = ledgerMapper.selectByIdempotencyKey(idempotencyKey);
        return entry == null ? null : toVo(entry);
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
