package com.wzblog.modules.content.api.vo;

/**
 * 归档聚合项：年份必填，月份仅在指定 year 时返回。
 */
public record ArchiveItemVO(int year, Integer month, long count) {
}
