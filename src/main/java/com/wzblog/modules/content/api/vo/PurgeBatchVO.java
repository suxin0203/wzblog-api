package com.wzblog.modules.content.api.vo;

import java.util.List;

/**
 * 回收站批量彻底删除结果：逐篇独立事务，单篇失败不影响其余。
 */
public record PurgeBatchVO(
        List<Long> succeeded,
        List<Failed> failed) {

    public record Failed(Long id, String code) {
    }
}
