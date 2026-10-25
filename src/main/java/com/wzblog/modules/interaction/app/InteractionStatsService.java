package com.wzblog.modules.interaction.app;

import com.wzblog.modules.interaction.infra.mapper.InteractionStatsMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * M3 互动统计端口（M7 看板用，只读本模块表）。
 */
@Service
public class InteractionStatsService {

    private final InteractionStatsMapper statsMapper;

    public InteractionStatsService(InteractionStatsMapper statsMapper) {
        this.statsMapper = statsMapper;
    }

    @Transactional(readOnly = true)
    public long countCommentsCreatedBetween(LocalDateTime from, LocalDateTime to) {
        return statsMapper.countCommentsCreatedBetween(from, to);
    }

    @Transactional(readOnly = true)
    public long countReactionsBetween(String type, LocalDateTime from, LocalDateTime to) {
        return statsMapper.countReactionsBetween(type, from, to);
    }
}
