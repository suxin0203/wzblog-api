package com.wzblog.modules.wechat.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.wechat.infra.config.WechatProperties;
import com.wzblog.modules.wechat.infra.wechat.WeChatApiClient;
import org.springframework.stereotype.Service;

/**
 * code 换 openid 的 app 层出口（旧 /wechat/openid 代理）。
 * 纯转发 + 返回纯类型，使 api 层不触碰 infra（ArchUnit 分层规则）。
 */
@Service
public class WechatCodeService {

    private final WeChatApiClient weChatApi;
    private final WechatProperties properties;

    public WechatCodeService(WeChatApiClient weChatApi, WechatProperties properties) {
        this.weChatApi = weChatApi;
        this.properties = properties;
    }

    public record OpenidResult(String openid, String unionid) {
    }

    public OpenidResult code2Session(String code) {
        requireEnabled();
        WeChatApiClient.SessionResult session = weChatApi.code2Session(code);
        return new OpenidResult(session.openid(), session.unionid());
    }

    /** 模块关闭（无凭据且非显式 mock）：503，密码登录不受影响（docs/16 §4）。 */
    public void requireEnabled() {
        if (!properties.isWechatEnabled()) {
            throw new BusinessException(ErrorCode.WECHAT_NOT_ENABLED);
        }
    }
}
