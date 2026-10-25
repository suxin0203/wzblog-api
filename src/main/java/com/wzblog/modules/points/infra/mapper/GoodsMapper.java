package com.wzblog.modules.points.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.points.domain.Goods;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 商品与库存。扣减/回补为条件原子 UPDATE，防超卖（M4 §8.1/8.2）。
 */
@Mapper
public interface GoodsMapper extends BaseMapper<Goods> {

    String COLUMNS = """
            id, name, type, title_id, description, image_key, points_price, stock, sold_count,
            status, sort_order, version, created_at, updated_at, deleted_at
            """;

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM points_goods
            WHERE status = 'ON_SALE' AND deleted_at IS NULL
            ORDER BY sort_order ASC, id ASC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<Goods> selectPublicPage(@Param("limit") int limit, @Param("offset") int offset);

    @Select("SELECT COUNT(*) FROM points_goods WHERE status = 'ON_SALE' AND deleted_at IS NULL")
    long countPublic();

    @Select("SELECT " + COLUMNS + """
            FROM points_goods
            WHERE id = #{id} AND status = 'ON_SALE' AND deleted_at IS NULL
            """)
    Goods selectPublicById(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM points_goods WHERE id = #{id}")
    Goods selectByIdIncludeDeleted(@Param("id") Long id);

    @Select("SELECT " + COLUMNS + " FROM points_goods WHERE id = #{id} FOR UPDATE")
    Goods selectForUpdate(@Param("id") Long id);

    /** 条件扣库存：NULL=不限量直过；不足影响 0 行。 */
    @Update("""
            UPDATE points_goods
            SET stock = CASE WHEN stock IS NULL THEN NULL ELSE stock - #{quantity} END,
                sold_count = sold_count + #{quantity}, version = version + 1,
                updated_at = #{now}
            WHERE id = #{id} AND status = 'ON_SALE' AND deleted_at IS NULL
              AND (stock IS NULL OR stock >= #{quantity})
            """)
    int deductStock(@Param("id") Long id, @Param("quantity") int quantity,
                    @Param("now") LocalDateTime now);

    /** 取消/驳回回补库存。 */
    @Update("""
            UPDATE points_goods
            SET stock = CASE WHEN stock IS NULL THEN NULL ELSE stock + #{quantity} END,
                sold_count = GREATEST(sold_count - #{quantity}, 0), version = version + 1,
                updated_at = #{now}
            WHERE id = #{id}
            """)
    int restoreStock(@Param("id") Long id, @Param("quantity") int quantity,
                     @Param("now") LocalDateTime now);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM points_goods
            <where>
                <if test="status != null and status != ''"> AND status = #{status}</if>
                <if test="type != null and type != ''"> AND type = #{type}</if>
                <if test="keyword != null and keyword != ''"> AND name LIKE CONCAT('%', #{keyword}, '%')</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<Goods> selectAdminPage(@Param("status") String status, @Param("type") String type,
                                @Param("keyword") String keyword,
                                @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM points_goods
            <where>
                <if test="status != null and status != ''"> AND status = #{status}</if>
                <if test="type != null and type != ''"> AND type = #{type}</if>
                <if test="keyword != null and keyword != ''"> AND name LIKE CONCAT('%', #{keyword}, '%')</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("status") String status, @Param("type") String type,
                        @Param("keyword") String keyword);

    @Update("""
            UPDATE points_goods
            SET deleted_at = #{now}, updated_at = #{now}
            WHERE id = #{id} AND deleted_at IS NULL
            """)
    int softDelete(@Param("id") Long id, @Param("now") LocalDateTime now);
}
