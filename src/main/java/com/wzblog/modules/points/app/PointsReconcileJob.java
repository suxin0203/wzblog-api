package com.wzblog.modules.points.app;

import com.wzblog.modules.points.domain.PointsAccount;
import com.wzblog.modules.points.domain.PointsType;
import com.wzblog.modules.points.infra.mapper.PointsAccountMapper;
import com.wzblog.modules.points.infra.mapper.PointsLedgerMapper;
import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.common.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * 积分对账（M4 §9）：每日 04:00 校验 balance = Σamount，差异追加 ADMIN_ADJUST 修复流水。
 */
@Service
public class PointsReconcileJob {

    private static final Logger log = LoggerFactory.getLogger(PointsReconcileJob.class);
    private static final String LOCK_KEY = "wzblog:points:reconcile-lock";

    private final PointsLedgerMapper ledgerMapper;
    private final PointsAccountMapper accountMapper;
    private final PointsAppService pointsAppService;
    private final AuditRecorder auditRecorder;
    private final StringRedisTemplate redis;

    public PointsReconcileJob(PointsLedgerMapper ledgerMapper,
                              PointsAccountMapper accountMapper,
                              PointsAppService pointsAppService,
                              AuditRecorder auditRecorder,
                              StringRedisTemplate redis) {
        this.ledgerMapper = ledgerMapper;
        this.accountMapper = accountMapper;
        this.pointsAppService = pointsAppService;
        this.auditRecorder = auditRecorder;
        this.redis = redis;
    }

    @Scheduled(cron = "0 0 4 * * *", zone = "Asia/Shanghai")
    public void scheduled() {
        run();
    }

    public void run() {
        com.wzblog.common.lock.OwnerAwareRedisLock lock =
                new com.wzblog.common.lock.OwnerAwareRedisLock(redis, LOCK_KEY);
        if (!lock.tryAcquire(Duration.ofMinutes(30))) {
            log.info("积分对账跳过：锁被其他实例持有");
            return;
        }
        try {
            var mismatches = ledgerMapper.selectBalanceMismatches();
            if (mismatches.isEmpty()) {
                return;
            }
            String date = LocalDate.now(ZoneId.of("Asia/Shanghai")).toString();
            for (var row : mismatches) {
                int delta = (int) (row.ledgerSum() - row.balance());
                if (delta == 0) {
                    continue;
                }
                var result = pointsAppService.post(row.userId(), PointsType.ADMIN_ADJUST, delta,
                        "SYSTEM", null, "reconcile:" + row.userId() + ":" + date,
                        "RECONCILE_FIX: balance=" + row.balance() + " ledgerSum=" + row.ledgerSum(),
                        null);
                auditRecorder.record(null, "POINTS_RECONCILE", "USER",
                        String.valueOf(row.userId()),
                        "{\"balance\":" + row.balance() + "}",
                        "{\"ledgerSum\":" + row.ledgerSum() + ",\"delta\":" + delta + "}");
                log.warn("积分对账修复 userId={} delta={} ledgerId={}", row.userId(), delta,
                        result.ledgerId());
            }
        } finally {
            lock.release();
        }
    }
}
