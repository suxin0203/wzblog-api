package com.wzblog.modules.media.domain;

/**
 * 文件状态机（M5 §5.2）：TEMP → ACTIVE ⇄ ORPHAN → DELETED（终态）。
 */
public enum FileStatus {
    TEMP,
    ACTIVE,
    ORPHAN,
    DELETED
}
