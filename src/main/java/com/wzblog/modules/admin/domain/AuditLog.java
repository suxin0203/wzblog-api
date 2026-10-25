package com.wzblog.modules.admin.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * audit_logs 表实体：管理端操作审计。
 */
@Getter
@Setter
@TableName("audit_logs")
public class AuditLog {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long operatorId;
    private String action;
    private String targetType;
    private String targetId;
    private String beforeJson;
    private String afterJson;
    private String ip;
    private LocalDateTime createdAt;
}
