package com.wzblog.modules.identity.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.identity.domain.LoginAudit;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface LoginAuditMapper extends BaseMapper<LoginAudit> {
}