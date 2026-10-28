package com.wzblog.modules.points.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.modules.points.domain.LedgerEntry;
import com.wzblog.modules.points.domain.PointsAccount;
import com.wzblog.modules.points.domain.PointsType;
import com.wzblog.modules.points.infra.config.PointsProperties;
import com.wzblog.modules.points.infra.mapper.PointsAccountMapper;
import com.wzblog.modules.points.infra.mapper.PointsLedgerMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * 账本统一入口（M4 §7）：全系统唯一允许写 points_ledger/points_accounts 的服务。
 * <p>事务步骤：幂等快路径 → 懒创建+行锁账户 → 锁内日上限 → 写流水（uk 幂等）→ 更新快照。
 * 锁序恒为 账户 → 流水；涉及商品的调用方必须先锁商品再进入本服务。</p>
 */
@Service
public class PointsAppService {

    private static final Logger log = LoggerFactory.getLogger(PointsAppService.class);
    private static final DateTimeFormatter BUSINESS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final PointsAccountMapper accountMapper;
    private final PointsLedgerMapper ledgerMapper;
    private final PointsProperties properties;

    public PointsAppService(PointsAccountMapper accountMapper,
                            PointsLedgerMapper ledgerMapper,
                            PointsProperties properties) {
        this.accountMapper = accountMapper;
        this.ledgerMapper = ledgerMapper;
        this.properties = properties;
    }

    /** 记账结果：duplicate 表示幂等命中已有流水。 */
    public record PointsResult(long ledgerId, int grantedAmount, int balanceAfter, boolean duplicate) {

        static PointsResult empty() {
            return new PointsResult(0, 0, 0, false);
        }
    }

    /** 调用方自定义金额的发放（退款/调分/反转），amount 可为负。 */
    @Transactional
    public PointsResult post(Long userId, PointsType type, int amount, String bizType,
                             String bizId, String idempotencyKey, String remark, Long operatorId) {
        if (amount == 0) {
            return PointsResult.empty();
        }
        LedgerEntry existing = ledgerMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return new PointsResult(existing.getId(), existing.getAmount(),
                    existing.getBalanceAfter(), true);
        }
        ensureAndLock(userId);
        PointsAccount account = accountMapper.selectForUpdate(userId);
        int balance = account == null ? 0 : account.getBalance();
        int signed = amount > 0 ? amount : Math.max(amount, -balance); // 反转不产生负余额
        if (signed == 0) {
            log.warn("积分反转余额不足：userId={} type={} 期望扣减 {} 余额 {}，跳过流水",
                    userId, type, -amount, balance);
            return PointsResult.empty();
        }
        int newBalance = balance + signed;
        String finalRemark = remark;
        if (signed != amount && amount < 0) {
            finalRemark = (remark == null ? "" : remark + "；") + "余额不足，少扣 " + (-amount - -signed);
        }
        LedgerEntry entry = insertLedger(userId, signed, newBalance, type, bizType, bizId,
                idempotencyKey, finalRemark, operatorId);
        if (entry == null) {
            return duplicateResult(idempotencyKey); // 并发重复：uk 兜底，不再更新快照
        }
        updateSnapshot(userId, account, signed);
        return new PointsResult(entry.getId(), signed, newBalance, false);
    }

    /** 事件发放：按 6.1 计算日上限后的金额。 */
    @Transactional
    public PointsResult grant(Long userId, PointsType type, int unitAmount, int dailyCap,
                              String bizType, String bizId, String idempotencyKey, String remark) {
        LedgerEntry existing = ledgerMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return new PointsResult(existing.getId(), existing.getAmount(),
                    existing.getBalanceAfter(), true);
        }
        ensureAndLock(userId);
        PointsAccount account = accountMapper.selectForUpdate(userId);
        int balance = account == null ? 0 : account.getBalance();

        int granted = unitAmount;
        if (dailyCap > 0) {
            // 日上限必须在账户行锁内计算（业务日 = Asia/Shanghai 的自然日）
            var window = BusinessDayWindow.today();
            long earned = ledgerMapper.sumPositiveInRange(userId, type.name(),
                    window.fromUtc(), window.toUtc());
            granted = Math.min(unitAmount, (int) Math.max(0, dailyCap - earned));
        }
        if (granted <= 0) {
            return PointsResult.empty(); // 达上限：不写流水
        }
        int newBalance = balance + granted;
        LedgerEntry entry = insertLedger(userId, granted, newBalance, type, bizType, bizId,
                idempotencyKey, remark, null);
        if (entry == null) {
            return duplicateResult(idempotencyKey); // 并发重复：uk 兜底，不再更新快照
        }
        updateSnapshot(userId, account, granted);
        return new PointsResult(entry.getId(), granted, newBalance, false);
    }

    /** 消费扣减（REDEEM）：余额不足抛 422，调用方事务回滚。 */
    @Transactional
    public PointsResult deduct(Long userId, int amount, PointsType type, String bizType,
                               String bizId, String idempotencyKey, String remark) {
        LedgerEntry existing = ledgerMapper.selectByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return new PointsResult(existing.getId(), existing.getAmount(),
                    existing.getBalanceAfter(), true);
        }
        ensureAndLock(userId);
        PointsAccount account = accountMapper.selectForUpdate(userId);
        int balance = account == null ? 0 : account.getBalance();
        if (balance < amount) {
            throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
        }
        int newBalance = balance - amount;
        LedgerEntry entry = insertLedger(userId, -amount, newBalance, type, bizType, bizId,
                idempotencyKey, remark, null);
        if (entry == null) {
            return duplicateResult(idempotencyKey); // 并发重复：uk 兜底，不再更新快照
        }
        updateSnapshot(userId, account, -amount);
        return new PointsResult(entry.getId(), -amount, newBalance, false);
    }

    /** 反转：按该业务已发放合计收敛，返回 null 表示无可反转。 */
    @Transactional
    public PointsResult reverse(Long userId, PointsType reverseType, PointsType positiveType,
                                String bizType, String bizId, String idempotencyKey) {
        long grantedTotal = ledgerMapper.sumPositiveByBiz(userId, positiveType.name(), bizType,
                bizId);
        if (grantedTotal <= 0) {
            return PointsResult.empty(); // 未发放过，跳过
        }
        return post(userId, reverseType, -(int) grantedTotal, bizType, bizId, idempotencyKey,
                positiveType.name() + " 反转", null);
    }

    /**
     * 按业务键找到原收款人并反转（D9）：文章被接管后 author_id 会变，
     * 但历史积分属于原发放对象，必须按流水回查收款人。
     */
    @Transactional
    public PointsResult reverseByBiz(PointsType reverseType, PointsType positiveType,
                                     String bizType, String bizId, String idempotencyKeyPrefix) {
        java.util.List<LedgerEntry> positives =
                ledgerMapper.selectPositiveByBiz(positiveType.name(), bizType, bizId);
        if (positives.isEmpty()) {
            return PointsResult.empty();
        }
        java.util.Map<Long, Integer> byUser = new java.util.LinkedHashMap<>();
        for (LedgerEntry entry : positives) {
            byUser.merge(entry.getUserId(), entry.getAmount(), Integer::sum);
        }
        PointsResult last = PointsResult.empty();
        for (var entry : byUser.entrySet()) {
            last = post(entry.getKey(), reverseType, -entry.getValue(), bizType, bizId,
                    idempotencyKeyPrefix + ":" + entry.getKey(),
                    positiveType.name() + " 反转", null);
        }
        return last;
    }

    /** 精确反转（按正向幂等键回查收款人）：点赞取消在接管后也要退给原作者。 */
    @Transactional
    public PointsResult reverseExactByKey(PointsType reverseType, String positiveKey,
                                          String reverseKey, String bizType, String bizId) {
        LedgerEntry positive = ledgerMapper.selectByIdempotencyKey(positiveKey);
        if (positive == null || positive.getAmount() <= 0) {
            return PointsResult.empty();
        }
        return post(positive.getUserId(), reverseType, -positive.getAmount(), bizType, bizId,
                reverseKey, reverseType.name() + " 反转", null);
    }

    // ------------------------------------------------------------ 内部

    private void ensureAndLock(Long userId) {
        accountMapper.ensureAccount(userId);
    }

    private LedgerEntry insertLedger(Long userId, int signedAmount, int balanceAfter,
                                     PointsType type, String bizType, String bizId,
                                     String idempotencyKey, String remark, Long operatorId) {
        LedgerEntry entry = new LedgerEntry();
        entry.setUserId(userId);
        entry.setAmount(signedAmount);
        entry.setBalanceAfter(balanceAfter);
        entry.setType(type.name());
        entry.setBizType(bizType);
        entry.setBizId(bizId);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setOperatorId(operatorId);
        entry.setRemark(remark);
        entry.setCreatedAt(Times.nowUtc());
        try {
            ledgerMapper.insertPlain(entry);
            return entry;
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            return null; // 并发重复：uk_idempotency 兜底
        }
    }

    private void updateSnapshot(Long userId, PointsAccount account, int signedAmount) {
        int earned = (account == null ? 0 : account.getTotalEarned())
                + (signedAmount > 0 ? signedAmount : 0);
        int spent = (account == null ? 0 : account.getTotalSpent())
                + (signedAmount < 0 ? -signedAmount : 0);
        int balance = (account == null ? 0 : account.getBalance()) + signedAmount;
        accountMapper.updateSnapshot(userId, balance, earned, spent, Times.nowUtc());
    }

    /**
     * 并发重复（uk 幂等兜底命中）：回读原流水返回 duplicate 结果。
     * 不抛异常——内层事务异常会把共享事务标记 rollback-only。
     * REPEATABLE READ 下同事务可能读不到并行提交的行，读不到时按 0 处理，
     * 下一笔同键事件会命中快路径。
     */
    private PointsResult duplicateResult(String idempotencyKey) {
        LedgerEntry winner = ledgerMapper.selectByIdempotencyKey(idempotencyKey);
        return winner == null ? PointsResult.empty()
                : new PointsResult(winner.getId(), winner.getAmount(), winner.getBalanceAfter(),
                        true);
    }

    /** 业务日窗口：Asia/Shanghai 自然日 [D 00:00, D+1 00:00) 折算为 UTC。 */
    record BusinessDayWindow(LocalDateTime fromUtc, LocalDateTime toUtc) {

        static BusinessDayWindow today() {
            return of(java.time.LocalDateTime.now(ZoneOffset.UTC));
        }

        static BusinessDayWindow of(LocalDateTime anyUtc) {
            LocalDateTime shanghai = anyUtc.atZone(ZoneOffset.UTC)
                    .withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")).toLocalDateTime();
            LocalDateTime dayStartSh = shanghai.toLocalDate().atStartOfDay();
            LocalDateTime from = dayStartSh.atZone(java.time.ZoneId.of("Asia/Shanghai"))
                    .withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime();
            return new BusinessDayWindow(from, from.plus(Duration.ofDays(1)));
        }

        static String businessDate(java.time.Instant instant) {
            return BUSINESS_DATE.format(instant.atZone(java.time.ZoneId.of("Asia/Shanghai")));
        }
    }
}
