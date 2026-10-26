package com.wzblog.modules.wechat.infra.wechat;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;

/**
 * 微信上游 API 端口（M8 设计文档 §6）。Mock/Real 双实现（同 M6 ChatClient 模式）。
 */
public interface WeChatApiClient {

    /** code 换 openid/unionid（jscode2session）。session_key 永不外传。 */
    SessionResult code2Session(String code);

    /**
     * 生成小程序码 PNG 字节（getwxacodeunlimit）。
     *
     * @param envVersion release/trial/develop；null 用默认配置
     */
    byte[] getQrCodePng(String sceneId, String envVersion);

    /** 上游错误统一转业务异常（映射表 M8 §6.4）。 */
    record SessionResult(String openid, String unionid) {
    }

    /** 上游 errcode → 业务错误码（M8 设计文档 §6.4）。 */
    static BusinessException mapUpstreamError(int errcode, String errmsg) {
        return switch (errcode) {
            case 40029, 40163 -> new BusinessException(ErrorCode.WECHAT_CODE_INVALID);
            case 45011 -> new BusinessException(ErrorCode.WECHAT_RATE_LIMITED,
                    ErrorCode.WECHAT_RATE_LIMITED.defaultMessage(), 60L);
            case 40226 -> new BusinessException(ErrorCode.WECHAT_RISK_REJECTED);
            default -> new BusinessException(ErrorCode.WECHAT_API_ERROR);
        };
    }
}
