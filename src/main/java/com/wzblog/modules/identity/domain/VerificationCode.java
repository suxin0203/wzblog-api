package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * verification_codes 表实体：邮箱验证码只存哈希。
 */
@Getter
@Setter
@TableName("verification_codes")
public class VerificationCode {

    @TableId(type = IdType.AUTO)
    private Long id;
    private VerificationScene scene;
    private String target;
    private String codeHash;
    private LocalDateTime expiresAt;
    private LocalDateTime consumedAt;
    private Integer attemptCount;
    private LocalDateTime createdAt;
}