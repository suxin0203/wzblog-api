package com.wzblog.modules.admin.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.admin.domain.Banner;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 轮播读写。
 */
@Mapper
public interface BannerMapper extends BaseMapper<Banner> {

    @Select("""
            SELECT * FROM banners
            WHERE status = 'ENABLED'
              AND (start_at IS NULL OR start_at <= NOW(3))
              AND (end_at IS NULL OR end_at > NOW(3))
            ORDER BY sort_order ASC, id ASC
            """)
    List<Banner> selectActive();
}
