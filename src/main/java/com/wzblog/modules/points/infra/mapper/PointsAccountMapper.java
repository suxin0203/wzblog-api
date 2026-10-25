package com.wzblog.modules.points.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.points.domain.PointsAccount;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 积分账户读写。账户行是记账串行化点：懒创建 + FOR UPDATE。
 */
@Mapper
public interface PointsAccountMapper extends BaseMapper<PointsAccount> {

    /** 懒创建账户行（幂等）。 */
    @Insert("""
            INSERT INTO points_accounts (user_id) VALUES (#{userId})
            ON DUPLICATE KEY UPDATE user_id = user_id
            """)
    void ensureAccount(@Param("userId") Long userId);

    @Select("""
            SELECT user_id, balance, total_earned, total_spent, version, created_at, updated_at
            FROM points_accounts WHERE user_id = #{userId} FOR UPDATE
            """)
    PointsAccount selectForUpdate(@Param("userId") Long userId);

    @Select("""
            SELECT user_id, balance, total_earned, total_spent, version, created_at, updated_at
            FROM points_accounts WHERE user_id = #{userId}
            """)
    PointsAccount selectByUserId(@Param("userId") Long userId);

    @Update("""
            UPDATE points_accounts
            SET balance = #{balance}, total_earned = #{totalEarned}, total_spent = #{totalSpent},
                version = version + 1, updated_at = #{updatedAt}
            WHERE user_id = #{userId}
            """)
    int updateSnapshot(@Param("userId") Long userId, @Param("balance") int balance,
                       @Param("totalEarned") int totalEarned, @Param("totalSpent") int totalSpent,
                       @Param("updatedAt") LocalDateTime updatedAt);
}
