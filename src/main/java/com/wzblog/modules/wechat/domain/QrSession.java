package com.wzblog.modules.wechat.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * wechat_qr_sessions 表实体（V1 已建表）：状态机事实源与审计，热点态在 Redis。
 */
@Getter
@Setter
@TableName("wechat_qr_sessions")
public class QrSession {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String COMPLETED = "COMPLETED";
    public static final String EXPIRED = "EXPIRED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String sceneId;
    private String status;
    private Long userId;
    private String tempOpenid;
    private String bindTokenHash;
    private LocalDateTime bindExpiresAt;
    private String clientIp;
    private String userAgent;
    private LocalDateTime expiresAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
