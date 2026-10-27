package com.wzblog.modules.points.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.points.domain.Title;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 称号定义与用户授予。
 */
@Mapper
public interface TitleMapper extends BaseMapper<Title> {

    @Select("""
            SELECT id, name, css_class, description, sort_order, created_at, updated_at, deleted_at
            FROM titles WHERE deleted_at IS NULL ORDER BY sort_order ASC, id ASC
            """)
    List<Title> selectActive();

    @Select("""
            SELECT id, name, css_class, description, sort_order, created_at, updated_at, deleted_at
            FROM titles ORDER BY id DESC
            """)
    List<Title> selectAdminAll();

    @Select("""
            SELECT id, name, css_class, description, sort_order, created_at, updated_at, deleted_at
            FROM titles WHERE id = #{id}
            """)
    Title selectByIdIncludeDeleted(@Param("id") Long id);

    @Select("""
            SELECT id FROM titles WHERE name = #{name} AND (deleted_at IS NULL OR id != #{excludeId})
            LIMIT 1
            """)
    Long selectIdByName(@Param("name") String name, @Param("excludeId") Long excludeId);

    @Update("""
            UPDATE titles SET deleted_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 授予称号（联合主键幂等）。 */
    @Insert("""
            INSERT IGNORE INTO user_titles (user_id, title_id, source_order_id)
            VALUES (#{userId}, #{titleId}, #{sourceOrderId})
            """)
    int grantToUser(@Param("userId") Long userId, @Param("titleId") Long titleId,
                    @Param("sourceOrderId") Long sourceOrderId);

    @Select("SELECT COUNT(*) FROM user_titles WHERE user_id = #{userId} AND title_id = #{titleId}")
    int userOwnsTitle(@Param("userId") Long userId, @Param("titleId") Long titleId);

    /** 撤销指定订单授予的称号（TITLE 订单取消时）。 */
    @org.apache.ibatis.annotations.Delete(
            "DELETE FROM user_titles WHERE source_order_id = #{orderId}")
    int revokeByOrder(@Param("orderId") Long orderId);

    /** 账户视图：已授予称号 + active 标记（active_title_id 由 users 表维护）。 */
    @Select("""
            SELECT t.id AS id, t.name AS name, t.css_class AS cssClass, ut.acquired_at AS acquiredAt,
                   COALESCE(t.id = #{activeTitleId}, 0) AS active
            FROM user_titles ut JOIN titles t ON t.id = ut.title_id
            WHERE ut.user_id = #{userId}
            ORDER BY ut.acquired_at DESC, t.id DESC
            """)
    List<UserTitleRow> selectUserTitles(@Param("userId") Long userId,
                                        @Param("activeTitleId") Long activeTitleId);

    record UserTitleRow(Long id, String name, String cssClass,
                        java.time.LocalDateTime acquiredAt, boolean active) {
    }
}
