package com.wzblog.common.profile;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * 称号查询端口（M0 定义，M4 提供实现）。不存在返回 empty，不抛异常。
 */
public interface TitleLookup {

    Optional<TitleBrief> findById(Long titleId);

    Map<Long, TitleBrief> findByIds(Collection<Long> titleIds);
}