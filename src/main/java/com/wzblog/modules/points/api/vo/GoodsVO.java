package com.wzblog.modules.points.api.vo;

import java.time.Instant;

/**
 * 商品视图。
 */
public record GoodsVO(String id, String name, String type, String titleId, String titleName,
                      String description, String imageKey, int pointsPrice, Integer stock,
                      int soldCount, String status, int sortOrder, Instant createdAt) {
}
