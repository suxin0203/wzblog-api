package com.wzblog.modules.content.app.port;

import java.util.Map;

/**
 * M7 日阅读回填：{@code articleViews} 为 articleId → 当日增量，{@code internal/external} 为来源合计。
 */
public record DailyViews(Map<Long, Integer> articleViews, int internal, int external) {

    public boolean isEmpty() {
        return articleViews.isEmpty() && internal == 0 && external == 0;
    }
}
