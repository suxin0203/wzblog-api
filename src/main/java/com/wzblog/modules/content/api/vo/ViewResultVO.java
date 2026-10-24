package com.wzblog.modules.content.api.vo;

/**
 * 阅读上报结果：去重命中时 {@code counted=false}。
 */
public record ViewResultVO(boolean counted, int viewCount) {
}
