package com.wzblog.modules.media.api.vo;

import java.time.Instant;

/**
 * 清理结果（M5 §4.2）。
 */
public record CleanupVO(boolean dryRun, Instant startedAt, Instant finishedAt,
                        int tempScanned, int tempDeleted, int orphanMarked,
                        int orphanDeleted, int objectDeleteFailed) {
}
