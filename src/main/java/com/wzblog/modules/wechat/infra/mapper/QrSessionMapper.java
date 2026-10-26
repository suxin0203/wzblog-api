package com.wzblog.modules.wechat.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.wechat.domain.QrSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 扫码会话读写；状态迁移一律条件更新，影响行数由服务层判定。
 */
@Mapper
public interface QrSessionMapper extends BaseMapper<QrSession> {

    @Select("SELECT * FROM wechat_qr_sessions WHERE scene_id = #{sceneId} LIMIT 1")
    QrSession selectBySceneId(@Param("sceneId") String sceneId);

    /** entry：写 temp_openid + bindToken（仍 PENDING）；条件含未过期防竞态。 */
    @Update("""
            UPDATE wechat_qr_sessions
            SET temp_openid = #{tempOpenid}, bind_token_hash = #{bindTokenHash},
                bind_expires_at = #{bindExpiresAt}, updated_at = #{now}
            WHERE scene_id = #{sceneId} AND status = 'PENDING' AND expires_at > #{now}
            """)
    int saveEntryState(@Param("sceneId") String sceneId,
                       @Param("tempOpenid") String tempOpenid,
                       @Param("bindTokenHash") String bindTokenHash,
                       @Param("bindExpiresAt") LocalDateTime bindExpiresAt,
                       @Param("now") LocalDateTime now);

    /** 确认：PENDING → CONFIRMED（绑定 user_id）；条件更新保证并发单胜。 */
    @Update("""
            UPDATE wechat_qr_sessions
            SET status = 'CONFIRMED', user_id = #{userId}, updated_at = #{now}
            WHERE scene_id = #{sceneId} AND status = 'PENDING' AND expires_at > #{now}
            """)
    int confirm(@Param("sceneId") String sceneId, @Param("userId") Long userId,
                @Param("now") LocalDateTime now);

    /** 兑换完成：CONFIRMED → COMPLETED（仅一次）。 */
    @Update("""
            UPDATE wechat_qr_sessions
            SET status = 'COMPLETED', updated_at = #{now}
            WHERE scene_id = #{sceneId} AND status = 'CONFIRMED'
            """)
    int complete(@Param("sceneId") String sceneId, @Param("now") LocalDateTime now);

    /** 懒过期：pending/confirmed 且超时 → EXPIRED。 */
    @Update("""
            UPDATE wechat_qr_sessions
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE scene_id = #{sceneId} AND status IN ('PENDING', 'CONFIRMED')
              AND expires_at <= #{now}
            """)
    int expireIfDue(@Param("sceneId") String sceneId, @Param("now") LocalDateTime now);

    /** 清理任务：批量过期（走 idx_status_expires）。 */
    @Update("""
            UPDATE wechat_qr_sessions
            SET status = 'EXPIRED', updated_at = #{now}
            WHERE status IN ('PENDING', 'CONFIRMED') AND expires_at <= #{now}
            LIMIT 500
            """)
    int expireBatch(@Param("now") LocalDateTime now);
}
