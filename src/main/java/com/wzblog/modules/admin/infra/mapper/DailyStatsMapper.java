package com.wzblog.modules.admin.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.admin.domain.DailyStat;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDate;
import java.util.List;

/**
 * 日统计读写（UPSERT 幂等，可重跑）。
 */
@Mapper
public interface DailyStatsMapper extends BaseMapper<DailyStat> {

    @Insert("""
            INSERT INTO daily_stats (stat_date, user_registered, article_published, comment_created,
                                     view_count, view_internal_count, view_external_count,
                                     like_count, favorite_count, ai_message_count,
                                     points_issued, points_consumed)
            VALUES (#{statDate}, #{userRegistered}, #{articlePublished}, #{commentCreated},
                    #{viewCount}, #{viewInternalCount}, #{viewExternalCount},
                    #{likeCount}, #{favoriteCount}, #{aiMessageCount},
                    #{pointsIssued}, #{pointsConsumed})
            ON DUPLICATE KEY UPDATE
                user_registered = VALUES(user_registered),
                article_published = VALUES(article_published),
                comment_created = VALUES(comment_created),
                view_count = GREATEST(VALUES(view_count), view_count),
                view_internal_count = GREATEST(VALUES(view_internal_count), view_internal_count),
                view_external_count = GREATEST(VALUES(view_external_count), view_external_count),
                like_count = VALUES(like_count),
                favorite_count = VALUES(favorite_count),
                ai_message_count = VALUES(ai_message_count),
                points_issued = VALUES(points_issued),
                points_consumed = VALUES(points_consumed)
            """)
    int upsert(DailyStat stat);

    @Select("""
            SELECT * FROM daily_stats
            WHERE stat_date >= #{from} AND stat_date <= #{to}
            ORDER BY stat_date ASC
            """)
    List<DailyStat> selectRange(@Param("from") LocalDate from, @Param("to") LocalDate to);
}
