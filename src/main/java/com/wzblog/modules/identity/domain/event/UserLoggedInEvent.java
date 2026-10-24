package com.wzblog.modules.identity.domain.event;

import java.time.Instant;

/**
 * 登录成功事件（00 §9.1）：仅每日首次登录发布，M4 据此发放每日登录积分。
 */
public record UserLoggedInEvent(Long userId, Instant loginAt, String ip, boolean firstLoginOfDay) {
}