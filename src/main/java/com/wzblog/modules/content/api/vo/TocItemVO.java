package com.wzblog.modules.content.api.vo;

/**
 * 目录项（M2 §7）：{@code level} 为 1~6，{@code anchor} 与 HTML 中的 id 一致。
 */
public record TocItemVO(int level, String text, String anchor) {
}
