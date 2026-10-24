package com.wzblog.modules.content.app;

/**
 * 文章冗余计数类型（M2 §16.2 端口参数，不落库）。
 */
public enum CounterType {
    LIKE,
    FAVORITE,
    COMMENT
}
