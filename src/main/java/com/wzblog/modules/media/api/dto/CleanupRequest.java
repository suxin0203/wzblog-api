package com.wzblog.modules.media.api.dto;

/**
 * POST /admin/files/cleanup 请求（M5 §4.1）。
 */
public record CleanupRequest(Boolean dryRun) {

    public boolean dryRunOrDefault() {
        return dryRun == null || dryRun;
    }
}
