package com.wzblog.modules.points.api.vo;

import java.time.Instant;

/**
 * 用户已授予称号项。
 */
public record TitleItemVO(String id, String name, String cssClass, Instant acquiredAt,
                          boolean active) {
}
