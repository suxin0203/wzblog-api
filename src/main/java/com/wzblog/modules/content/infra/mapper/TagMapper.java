package com.wzblog.modules.content.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.content.domain.Tag;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface TagMapper extends BaseMapper<Tag> {

    @Select("SELECT * FROM tags WHERE slug = #{slug}")
    Tag selectBySlug(@Param("slug") String slug);
}