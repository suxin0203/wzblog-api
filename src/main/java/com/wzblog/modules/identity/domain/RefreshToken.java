package com.wzblog.modules.identity.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * refresh_tokens 表实体：只存 SHA-256，family 链式轮换。
 */
@Getter
@Setter
@TableName("refresh_tokens")
public class RefreshToken {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String tokenHash;
    private String familyId;
    private Long parentId;
    private Long replacedById;
    private String clientIp;
    private String userAgent;
    private LocalDateTime issuedAt;
    private LocalDateTime expiresAt;
    private LocalDateTime revokedAt;
    private RevokeReason revokeReason;

    public boolean isRevoked() {
        return revokedAt != null;
    }
}