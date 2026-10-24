package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.wzblog.common.security.Role;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * users 表实体（字段定义见 V1__schema.sql）。
 */
@Getter
@Setter
@TableName("users")
public class User {

    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String passwordHash;
    private String email;
    private LocalDateTime emailVerifiedAt;
    private String nickname;
    private String avatarKey;
    private String bio;
    private Role role;
    private UserStatus status;
    private UserSource source;
    private Long activeTitleId;
    private String openid;
    private String unionid;
    private LocalDateTime lastLoginAt;
    private String lastLoginIp;
    private Integer version;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime deletedAt;

    public boolean isDeleted() {
        return deletedAt != null;
    }
}