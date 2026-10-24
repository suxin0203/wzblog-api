package com.wzblog.common.media;

/**
 * 文件业务类型（00 §6.1 FileBizType）。M5 实现后按此值维护引用计数。
 */
public enum FileBizType {

    ARTICLE_COVER,
    CONTENT_IMAGE,
    AVATAR,
    BANNER,
    GOODS
}