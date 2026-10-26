package com.wzblog.modules.wechat.app;

import com.wzblog.modules.wechat.infra.wechat.WeChatApiClient;
import org.springframework.stereotype.Service;

/**
 * code 换 openid 的 app 层出口（旧 /wechat/openid 代理）。
 * 纯转发 + 返回纯类型，使 api 层不触碰 infra（ArchUnit 分层规则）。
 */
@Service
public class WechatCodeService {

    private final WeChatApiClient weChatApi;

    public WechatCodeService(WeChatApiClient weChatApi) {
        this.weChatApi = weChatApi;
    }

    public record OpenidResult(String openid, String unionid) {
    }

    public OpenidResult code2Session(String code) {
        WeChatApiClient.SessionResult session = weChatApi.code2Session(code);
        return new OpenidResult(session.openid(), session.unionid());
    }
}
