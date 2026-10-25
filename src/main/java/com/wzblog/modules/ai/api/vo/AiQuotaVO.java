package com.wzblog.modules.ai.api.vo;

import java.time.Instant;

/**
 * 当前主体剩余配额。
 */
public record AiQuotaVO(boolean enabled, String scope, Window minute, Window daily) {

    public record Window(int limit, long used, long remaining, Instant resetAt) {
    }
}
