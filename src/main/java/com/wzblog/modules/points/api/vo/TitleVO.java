package com.wzblog.modules.points.api.vo;

/**
 * 称号视图（不含用户关联）。
 */
public record TitleVO(String id, String name, String cssClass, String description,
                      int sortOrder) {
}
