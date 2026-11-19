package com.wzblog.modules.identity.infra.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 称号名只读查询（identity 侧）：users.active_title_id → titles.name 的解析。
 * titles 表归属称号体系，identity 只读名称，不维护称号数据（写侧在 points/admin）。
 */
@Mapper
public interface TitleNameMapper {

    record TitleName(Long id, String name) {
    }

    @Select("SELECT id, name FROM titles WHERE deleted_at IS NULL")
    List<TitleName> selectAllActive();
}
