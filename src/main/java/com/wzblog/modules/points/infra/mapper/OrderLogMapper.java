package com.wzblog.modules.points.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.points.domain.OrderLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 订单状态日志。
 */
@Mapper
public interface OrderLogMapper extends BaseMapper<OrderLog> {

    @Insert("""
            INSERT INTO points_order_logs (order_id, from_status, to_status, operator_id, remark, created_at)
            VALUES (#{orderId}, #{fromStatus}, #{toStatus}, #{operatorId}, #{remark}, #{createdAt})
            """)
    void insertLog(@Param("orderId") Long orderId, @Param("fromStatus") String fromStatus,
                   @Param("toStatus") String toStatus, @Param("operatorId") Long operatorId,
                   @Param("remark") String remark,
                   @Param("createdAt") java.time.LocalDateTime createdAt);

    @Select("""
            SELECT id, order_id, from_status, to_status, operator_id, remark, created_at
            FROM points_order_logs WHERE order_id = #{orderId} ORDER BY id ASC
            """)
    List<OrderLog> selectByOrder(@Param("orderId") Long orderId);
}
