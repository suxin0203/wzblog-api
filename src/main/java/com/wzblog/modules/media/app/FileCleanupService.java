package com.wzblog.modules.media.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.modules.media.api.vo.CleanupVO;
import com.wzblog.modules.media.domain.FileEntry;
import com.wzblog.modules.media.domain.FileStatus;
import com.wzblog.modules.media.infra.config.FileStorageProperties;
import com.wzblog.modules.media.infra.mapper.FileMapper;
import com.wzblog.modules.media.infra.storage.ObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * 清理任务（M5 §11）：Redis SETNX 分布式锁互斥；A 步 ACTIVE→ORPHAN、
 * B 步过期 TEMP 置删、C 步过期 ORPHAN 置删、D 步补偿对象删除。
 * 每行删除为单条件 UPDATE，自身原子，无需显式事务。
 */
@Service
public class FileCleanupService {

    private static final Logger log = LoggerFactory.getLogger(FileCleanupService.class);
    private static final String LOCK_KEY = "wzblog:media:cleanup-lock";
    private static final int BATCH = 200;
    private static final int MAX_BATCHES = 10;

    private final FileMapper fileMapper;
    private final ObjectStorage objectStorage;
    private final FileStorageProperties properties;
    private final StringRedisTemplate redis;

    public FileCleanupService(FileMapper fileMapper, ObjectStorage objectStorage,
                              FileStorageProperties properties, StringRedisTemplate redis) {
        this.fileMapper = fileMapper;
        this.objectStorage = objectStorage;
        this.properties = properties;
        this.redis = redis;
    }

    @Scheduled(cron = "${wzblog.file.cleanup-cron:0 0 3 * * *}", zone = "Asia/Shanghai")
    public void scheduled() {
        try {
            run(false);
        } catch (BusinessException ex) {
            if (ex.errorCode() == ErrorCode.FILE_CLEANUP_RUNNING) {
                log.info("清理任务跳过：锁被其他实例持有");
            }
        }
    }

    /** 手动触发（后台路由）；锁被持有返回 409 FILE_CLEANUP_RUNNING。 */
    public CleanupVO run(boolean dryRun) {
        Boolean acquired = redis.opsForValue().setIfAbsent(LOCK_KEY, "1", Duration.ofMinutes(30));
        if (!Boolean.TRUE.equals(acquired)) {
            throw new BusinessException(ErrorCode.FILE_CLEANUP_RUNNING);
        }
        try {
            return doRun(dryRun);
        } finally {
            redis.delete(LOCK_KEY);
        }
    }

    private CleanupVO doRun(boolean dryRun) {
        Instant startedAt = Instant.now();
        LocalDateTime now = Times.nowUtc();
        LocalDateTime tempThreshold = now.minus(properties.getConfirmTimeout());
        LocalDateTime orphanThreshold = now.minus(properties.getOrphanGrace());
        LocalDateTime deletedThreshold = now.minus(properties.getOrphanGrace());

        if (dryRun) {
            int expiredTemp = (int) fileMapper.countExpiredTemp(tempThreshold);
            return new CleanupVO(true, startedAt, Instant.now(),
                    expiredTemp, expiredTemp,
                    (int) fileMapper.countOrphanCandidates(orphanThreshold),
                    (int) fileMapper.countOrphanExpired(orphanThreshold), 0);
        }

        // A 步：ACTIVE ref=0 超宽限 → ORPHAN
        int orphanMarked = fileMapper.markOrphans(orphanThreshold, now);

        // B/C 步：过期 TEMP 与 ORPHAN 置删 + 删对象（每批 200，最多 10 批）
        int tempScanned = 0;
        int tempDeleted = 0;
        int orphanDeleted = 0;
        int objectDeleteFailed = 0;
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var candidates = fileMapper.selectCleanupCandidates(
                    tempThreshold, orphanThreshold, BATCH);
            if (candidates.isEmpty()) {
                break;
            }
            for (FileEntry entry : candidates) {
                tempScanned++;
                boolean isTemp = entry.getStatus() == FileStatus.TEMP;
                if (fileMapper.softDelete(entry.getId(), Times.nowUtc()) > 0) {
                    if (isTemp) {
                        tempDeleted++;
                    } else {
                        orphanDeleted++;
                    }
                    if (!deleteObjectQuietly(entry.getObjectKey())) {
                        objectDeleteFailed++;
                    }
                }
            }
            if (candidates.size() < BATCH) {
                break;
            }
        }

        // D 步：补偿已置删但对象可能残留的行（宽限期内）
        for (int batch = 0; batch < MAX_BATCHES; batch++) {
            var deletedRows = fileMapper.selectDeletedRecent(deletedThreshold, BATCH);
            boolean anyObject = false;
            for (FileEntry entry : deletedRows) {
                if (objectStorage.exists(entry.getObjectKey())) {
                    anyObject = true;
                    if (!deleteObjectQuietly(entry.getObjectKey())) {
                        objectDeleteFailed++;
                    }
                }
            }
            if (!anyObject || deletedRows.size() < BATCH) {
                break;
            }
        }

        log.info("媒体清理完成 orphanMarked={}, tempDeleted={}, orphanDeleted={}, failed={}",
                orphanMarked, tempDeleted, orphanDeleted, objectDeleteFailed);
        return new CleanupVO(false, startedAt, Instant.now(),
                tempScanned, tempDeleted, orphanMarked, orphanDeleted, objectDeleteFailed);
    }

    private boolean deleteObjectQuietly(String objectKey) {
        try {
            objectStorage.delete(objectKey);
            return true;
        } catch (Exception ex) {
            log.warn("对象删除失败: {}", objectKey, ex);
            return false;
        }
    }
}
