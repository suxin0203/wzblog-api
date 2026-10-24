package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * login_audits 表实体：每次登录尝试均写一行。
 */
@Getter
@Setter
@TableName("login_audits")
public class LoginAudit {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String usernameAttempt;
    private Boolean success;
    private LoginFailureReason failureReason;
    private String ip;
    private String userAgent;
    private LocalDateTime createdAt;
}