package com.wzblog.modules.admin.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.admin.domain.AuditLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 审计写入（查询路由在 M7 提供）。
 */
@Mapper
public interface AuditLogMapper extends BaseMapper<AuditLog> {
}
