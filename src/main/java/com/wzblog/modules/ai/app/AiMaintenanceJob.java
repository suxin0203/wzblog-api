package com.wzblog.modules.ai.app;

import com.wzblog.modules.ai.infra.mapper.AiMessageMapper;
import com.wzblog.common.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneOffset;

/**
 * M6 兜底任务：STREAMING 超时置 INTERRUPTED（每 5 分钟）。
 */
@Service
public class AiMaintenanceJob {

    private static final Logger log = LoggerFactory.getLogger(AiMaintenanceJob.class);

    private final AiMessageMapper messageMapper;

    public AiMaintenanceJob(AiMessageMapper messageMapper) {
        this.messageMapper = messageMapper;
    }

    @Scheduled(fixedDelay = 300_000, initialDelay = 300_000)
    public void markStaleStreaming() {
        int updated = messageMapper.markStaleInterrupted(
                LocalDateTime.now(ZoneOffset.UTC).minusMinutes(10), Times.nowUtc());
        if (updated > 0) {
            log.info("AI 流式兜底：{} 条 STREAMING 置为 INTERRUPTED", updated);
        }
    }
}
