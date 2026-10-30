package com.wzblog.modules.wechat.api.vo;

import java.time.LocalDateTime;

/**
 * 三个确认接口共用响应（M8 §4.2 QrConfirmVO）：
 * 仅 confirm 下发一次性 bindToken（60s）；register-and-confirm / bind-and-confirm 恒为 null。
 */
public record QrConfirmVO(String sceneId, String status, String bindToken,
                          LocalDateTime bindTokenExpiresAt) {
}
