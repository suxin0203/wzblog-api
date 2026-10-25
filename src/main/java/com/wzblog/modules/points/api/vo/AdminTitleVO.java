package com.wzblog.modules.points.api.vo;

import java.time.Instant;

/**
 * 后台称号视图（含软删标记）。
 */
public record AdminTitleVO(String id, String name, String cssClass, String description,
                           int sortOrder, boolean deleted, Instant createdAt) {
}
