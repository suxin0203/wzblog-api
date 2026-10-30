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

    /** 纯类型令牌对（含有效期秒数，供新契约 VO 直转）。 */
    public record IssuedTokens(String accessToken, int expiresIn,
                               String refreshToken, int refreshExpiresIn) {
    }

    /** 用户简版视图（纯类型）：openid 明文永不外泄，只暴露"是否已绑定"。 */
    public record WechatUserView(Long id, String username, String nickname, String avatarKey,
                                 String role, String status, boolean openidBound) {
    }

    @Transactional
    public WechatLogin loginOrCreate(String openid, String unionid) {
        var result = identityService.loginOrCreateByWechat(openid, unionid, null, null);
        return new WechatLogin(result.userId(),
                result.status() == UserStatus.ACTIVE, result.created());
    }

    @Transactional
    public WechatLogin loginOrCreate(String openid, String unionid, String nickname,
                                     String avatarKey) {
        var result = identityService.loginOrCreateByWechat(openid, unionid, nickname, avatarKey);
        return new WechatLogin(result.userId(),
                result.status() == UserStatus.ACTIVE, result.created());
    }

    /** 扫码注册并绑定（source=QR），返回新账号 id。 */
    @Transactional
    public Long registerAndBind(String username, String password, String nickname,
                                String openid, String unionid) {
        return identityService.registerAndBindWechat(username, password, nickname, openid, unionid);
    }

    /** 当前登录用户绑定 openid（幂等；冲突/换绑抛业务异常）。 */
    @Transactional
    public boolean bind(Long userId, String openid, String unionid) {
        identityService.bindWechat(userId, openid, unionid);
        return true;
    }

    /** 用户简版视图；不存在返回 null。 */
    public WechatUserView userView(Long userId) {
        var view = identityService.viewOf(userId);
        return view == null ? null
                : new WechatUserView(view.id(), view.username(), view.nickname(),
                        view.avatarKey(), view.role(), view.status(), view.openidBound());
    }

    /** 用户绑定的 openid（仅模块内部流转）。 */
    public String openidOf(Long userId) {
        return identityService.openidOf(userId);
    }

    @Transactional
    public IssuedTokens issueTokens(Long userId, ClientInfo client) {
        var pair = authAppService.issueTokens(userId, client);
        return new IssuedTokens(pair.accessToken(), pair.expiresIn(),
                pair.refreshToken(), pair.refreshExpiresIn());
    }
}
