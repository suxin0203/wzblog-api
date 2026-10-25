package com.wzblog.modules.points.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.points.domain.LedgerEntry;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 积分流水（只追加）。写方法仅 PointsAppService 调用（ArchUnit/评审约束）。
 */
@Mapper
public interface PointsLedgerMapper extends BaseMapper<LedgerEntry> {

    String COLUMNS = """
            id, user_id, amount, balance_after, type, biz_type, biz_id, idempotency_key,
            operator_id, remark, created_at
            """;

    /**
     * 流水插入（回填自增 id）。幂等兜底不用 ODKU——它不回填主键；
     * 重复键由服务层捕获 DuplicateKeyException 处理（MySQL 域内捕获安全）。
     */
    @Insert("""
            INSERT INTO points_ledger
                (user_id, amount, balance_after, type, biz_type, biz_id, idempotency_key,
                 operator_id, remark, created_at)
            VALUES
                (#{userId}, #{amount}, #{balanceAfter}, #{type}, #{bizType}, #{bizId},
                 #{idempotencyKey}, #{operatorId}, #{remark}, #{createdAt})
            """)
    @org.apache.ibatis.annotations.Options(useGeneratedKeys = true, keyProperty = "id")
    int insertPlain(LedgerEntry entry);

    @Select("SELECT " + COLUMNS + " FROM points_ledger WHERE idempotency_key = #{key} LIMIT 1")
    LedgerEntry selectByIdempotencyKey(@Param("key") String key);

    /** 日上限：业务日区间内正向合计（账户锁内调用）。 */
    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM points_ledger
            WHERE user_id = #{userId} AND type = #{type} AND amount > 0
              AND created_at >= #{fromUtc} AND created_at < #{toUtc}
            """)
    long sumPositiveInRange(@Param("userId") Long userId, @Param("type") String type,
                            @Param("fromUtc") LocalDateTime fromUtc,
                            @Param("toUtc") LocalDateTime toUtc);

    /** 按 biz 统计已发放（正向）合计，用于反转金额收敛。 */
    @Select("""
            SELECT COALESCE(SUM(amount), 0) FROM points_ledger
            WHERE user_id = #{userId} AND type = #{type} AND amount > 0
              AND biz_type = #{bizType} AND biz_id = #{bizId}
            """)
    long sumPositiveByBiz(@Param("userId") Long userId, @Param("type") String type,
                          @Param("bizType") String bizType, @Param("bizId") String bizId);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM points_ledger
            WHERE user_id = #{userId}
            <if test="type != null and type != ''"> AND type = #{type}</if>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<LedgerEntry> selectUserPage(@Param("userId") Long userId, @Param("type") String type,
                                     @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM points_ledger
            WHERE user_id = #{userId}
            <if test="type != null and type != ''"> AND type = #{type}</if>
            </script>
            """)
    long countUserPage(@Param("userId") Long userId, @Param("type") String type);

    @Select("""
            <script>
            SELECT
            """ + COLUMNS + """
            FROM points_ledger
            <where>
                <if test="userId != null"> AND user_id = #{userId}</if>
                <if test="type != null and type != ''"> AND type = #{type}</if>
                <if test="bizType != null and bizType != ''"> AND biz_type = #{bizType}</if>
                <if test="from != null"> AND created_at &gt;= #{from}</if>
                <if test="to != null"> AND created_at &lt; #{to}</if>
            </where>
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<LedgerEntry> selectAdminPage(@Param("userId") Long userId, @Param("type") String type,
                                      @Param("bizType") String bizType,
                                      @Param("from") LocalDateTime from,
                                      @Param("to") LocalDateTime to,
                                      @Param("limit") int limit, @Param("offset") int offset);

    @Select("""
            <script>
            SELECT COUNT(*) FROM points_ledger
            <where>
                <if test="userId != null"> AND user_id = #{userId}</if>
                <if test="type != null and type != ''"> AND type = #{type}</if>
                <if test="bizType != null and bizType != ''"> AND biz_type = #{bizType}</if>
                <if test="from != null"> AND created_at &gt;= #{from}</if>
                <if test="to != null"> AND created_at &lt; #{to}</if>
            </where>
            </script>
            """)
    long countAdminPage(@Param("userId") Long userId, @Param("type") String type,
                        @Param("bizType") String bizType, @Param("from") LocalDateTime from,
                        @Param("to") LocalDateTime to);

    /** 对账：余额 vs 流水合计。 */
    @Select("""
            SELECT a.user_id AS userId, a.balance AS balance, COALESCE(SUM(l.amount), 0) AS ledgerSum
            FROM points_accounts a LEFT JOIN points_ledger l ON l.user_id = a.user_id
            GROUP BY a.user_id, a.balance
            HAVING a.balance <> COALESCE(SUM(l.amount), 0)
            """)
    List<ReconcileRow> selectBalanceMismatches();

    record ReconcileRow(Long userId, Integer balance, Long ledgerSum) {
    }
}
