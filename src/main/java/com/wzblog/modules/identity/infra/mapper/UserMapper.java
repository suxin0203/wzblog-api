package com.wzblog.modules.identity.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.identity.domain.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface UserMapper extends BaseMapper<User> {

    /** 锁定用户行，用于刷新/改密/管理员注销的串行化。 */
    @Select("SELECT id FROM users WHERE id = #{id} FOR UPDATE")
    Long lockById(@Param("id") Long id);

    @Select("SELECT * FROM users WHERE openid = #{openid} LIMIT 1")
    User selectByOpenid(@Param("openid") String openid);

    @Select("SELECT * FROM users WHERE unionid = #{unionid} LIMIT 1")
    User selectByUnionid(@Param("unionid") String unionid);

    /** 统计除指定用户外仍可用的 ADMIN 数量（最后一个管理员保护）。 */
    @Select("""
            SELECT COUNT(*) FROM users
            WHERE role = 'ADMIN' AND status = 'ACTIVE' AND deleted_at IS NULL AND id <> #{excludeId}
            """)
    long countOtherActiveAdmins(@Param("excludeId") Long excludeId);
}