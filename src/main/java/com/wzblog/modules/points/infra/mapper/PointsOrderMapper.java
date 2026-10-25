package com.wzblog.modules.points.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.points.domain.PointsOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单读写。状态迁移一律条件更新（WHERE status=:from）。
 */
@Mapper
public interface PointsOrderMapper extends BaseMapper<PointsOrder> {

    String COLUMNS = """
            id, order_no, idempotency_key, user_id, goods_id, goods_name_snapshot,
            goods_type_snapshot, unit_points, quantity, total_points, status,
            receiver_name, receiver_phone, receiver_address, logistics_company, logistics_no,
            user_remark, admin_remark, cancelled_reason, approved_at, shipped_at, completed_at,
            cancelled_at, version, created_at, updated_at
            """;

    @Select("SELECT " + COLUMNS + " FROM points_orders WHERE order_no = #{orderNo}")
    PointsOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /** 幂等预检：同用户同键的既有订单。 */
    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM points_orders
            WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey}
            """)
    PointsOrder selectByIdempotency(@Param("userId") Long userId,
                                    @Param("idempotencyKey") String idempotencyKey);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM points_orders
            <where>
                user_id = #{userId}
                <if test="status != null and status != ''"> AND status = #{status}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PointsOrder> selectUserPage(@Param("userId") Long userId, @Param("status") String status,
                                     @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM points_orders
            WHERE user_id = #{userId}
            <if test="status != null and status != ''"> AND status = #{status}</if>
            </script>
            """)
    long countUserPage(@Param("userId") Long userId, @Param("status") String status);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM points_orders
            <where>
                <if test="userId != null"> AND user_id = #{userId}</if>
                <if test="status != null and status != ''"> AND status = #{status}</if>
                <if test="orderNo != null and orderNo != ''"> AND order_no = #{orderNo}</if>
                <if test="from != null"> AND created_at &gt;= #{from}</if>
                <if test="to != null"> AND created_at &lt; #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<PointsOrder> selectAdminPage(@Param("userId") Long userId, @Param("status") String status,
                                      @Param("orderNo") String orderNo,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM points_orders
            <where>
                <if test="userId != null"> AND user_id = #{userId}</if>
                <if test="status != null and status != ''"> AND status = #{status}</if>
                <if test="orderNo != null and orderNo != ''"> AND order_no = #{orderNo}</if>
                <if test="from != null"> AND created_at &gt;= #{from}</if>
                <if test="to != null"> AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("userId") Long userId, @Param("status") String status,
                        @Param("orderNo") String orderNo, @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

    /** 审核通过：TITLE 授予由服务层另行处理。 */
    @Update("""
            UPDATE points_orders
            SET status = 'APPROVED', approved_at = #{now}, admin_remark = #{adminRemark},
                version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND status = 'PENDING'
            """)
    int approve(@Param("id") Long id, @Param("adminRemark") String adminRemark,
                @Param("now") LocalDateTime now);

    @Update("""
            UPDATE points_orders
            SET status = 'SHIPPED', shipped_at = #{now}, logistics_company = #{company},
                logistics_no = #{logisticsNo}, version = version + 1, updated_at = #{now}
            WHERE id = #{id} AND status = 'APPROVED'
            """)
    int ship(@Param("id") Long id, @Param("company") String company,
             @Param("logisticsNo") String logisticsNo, @Param("now") LocalDateTime now);

    @Update("""
            UPDATE points_orders
            SET status = 'COMPLETED', completed_at = #{now}, version = version + 1,
                updated_at = #{now}
            WHERE id = #{id} AND status = 'SHIPPED'
            """)
    int complete(@Param("id") Long id, @Param("now") LocalDateTime now);

    /** 取消/驳回统一入口：from 由调用方限定（PENDING/APPROVED）。 */
    @Update("""
            UPDATE points_orders
            SET status = #{to}, cancelled_at = #{now}, cancelled_reason = #{reason},
                admin_remark = COALESCE(#{adminRemark}, admin_remark), version = version + 1,
                updated_at = #{now}
            WHERE id = #{id} AND status = #{from}
            """)
    int cancelOrReject(@Param("id") Long id, @Param("from") String from, @Param("to") String to,
                       @Param("reason") String reason, @Param("adminRemark") String adminRemark,
                       @Param("now") LocalDateTime now);
}
