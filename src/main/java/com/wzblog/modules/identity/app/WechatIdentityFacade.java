package com.wzblog.modules.identity.app;

import com.wzblog.common.web.ClientInfo;
import com.wzblog.modules.identity.domain.UserStatus;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信身份纯类型门面：给 wechat 模块用的最小出口，全部返回纯类型，
 * 避免 identity 的 api VO / domain 枚举泄入对方模块（ArchUnit 跨模块规则）。
 */
@Service
public class WechatIdentityFacade {

    private final WechatIdentityService identityService;
    private final AuthAppService authAppService;

    public WechatIdentityFacade(WechatIdentityService identityService,
                                @Lazy AuthAppService authAppService) {
        this.identityService = identityService;
        this.authAppService = authAppService;
    }

    /** 纯类型登录结果：active=false 表示停用（不签发 Token）。 */
    public record WechatLogin(Long userId, boolean active, boolean created) {
    }

    /** 纯类型令牌对。 */
    public record IssuedTokens(String accessToken, String refreshToken) {
    }

    @Transactional
    public WechatLogin loginOrCreate(String openid, String unionid) {
        var result = identityService.loginOrCreateByWechat(openid, unionid, null, null);
        return new WechatLogin(result.userId(),
                result.status() == UserStatus.ACTIVE, result.created());
    }

    @Transactional
    public IssuedTokens issueTokens(Long userId, ClientInfo client) {
        var pair = authAppService.issueTokens(userId, client);
        return new IssuedTokens(pair.accessToken(), pair.refreshToken());
    }
}
