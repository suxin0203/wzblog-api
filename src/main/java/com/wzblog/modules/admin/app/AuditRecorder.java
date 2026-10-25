package com.wzblog.modules.admin.app;

import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.domain.AuditLog;
import com.wzblog.modules.admin.infra.mapper.AuditLogMapper;
import com.wzblog.common.util.Times;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 审计写入器（M3 审核/M4 调分/M7 配置共用）：与业务同事务写入，失败由事务回滚。
 */
@Service
public class AuditRecorder {

    private static final Logger log = LoggerFactory.getLogger(AuditRecorder.class);

    private final AuditLogMapper auditLogMapper;

    public AuditRecorder(AuditLogMapper auditLogMapper) {
        this.auditLogMapper = auditLogMapper;
    }

    public void record(CurrentUser operator, String action, String targetType, String targetId,
                       String beforeJson, String afterJson) {
        AuditLog entry = new AuditLog();
        entry.setOperatorId(operator == null ? null : operator.id());
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setBeforeJson(beforeJson);
        entry.setAfterJson(afterJson);
        entry.setCreatedAt(Times.nowUtc());
        auditLogMapper.insert(entry);
        log.debug("audit action={} target={}/{} operator={}", action, targetType, targetId,
                operator == null ? null : operator.id());
    }
}
