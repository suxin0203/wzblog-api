package com.wzblog.modules.admin.infra.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.wzblog.modules.admin.domain.FriendLink;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 友链读写。
 */
@Mapper
public interface FriendLinkMapper extends BaseMapper<FriendLink> {

    @Select("SELECT * FROM friend_links WHERE status = 'ENABLED' ORDER BY sort_order ASC, id ASC")
    List<FriendLink> selectActive();
}
