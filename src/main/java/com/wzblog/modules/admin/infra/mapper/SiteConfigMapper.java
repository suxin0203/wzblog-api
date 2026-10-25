package com.wzblog.modules.admin.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.admin.domain.SiteConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 站点配置读取（写入路由在 M7 提供）。
 */
@Mapper
public interface SiteConfigMapper extends BaseMapper<SiteConfig> {

    @Select("SELECT id, config_key, config_value, value_type, description, is_public, updated_by, created_at, updated_at "
            + "FROM site_configs ORDER BY id")
    List<SiteConfig> selectAll();

    @Select("SELECT id, config_key, config_value, value_type, description, is_public, updated_by, created_at, updated_at "
            + "FROM site_configs WHERE is_public = 1 ORDER BY id")
    List<SiteConfig> selectPublic();
}
