package com.wzblog.modules.wechat.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.modules.identity.app.WechatIdentityFacade;
import org.springframework.stereotype.Service;

/**
 * 新契约小程序 code 登录编排（M8 设计 §7）：code → openid → M1 身份服务 → 双 Token。
 * 身份写入只经 WechatIdentityFacade（本模块不触碰 users 表，ArchUnit 分层规则）。
 */
@Service
public class WechatLoginService {

    private final WechatCodeService codeService;
    private final WechatIdentityFacade identityFacade;

    public WechatLoginService(WechatCodeService codeService, WechatIdentityFacade identityFacade) {
        this.codeService = codeService;
        this.identityFacade = identityFacade;
    }

    /** 登录结果：tokens + 简版视图 + 是否本次新建账号（openid 明文不出现在任何字段）。 */
    public record WechatLoginResult(WechatIdentityFacade.IssuedTokens tokens,
                                    WechatIdentityFacade.WechatUserView user, boolean created) {
    }

    /** code 换 openid，登录或自动创建正式 USER（source=WECHAT）；停用账号 403 不签发。 */
    public WechatLoginResult loginByCode(String code, String nickname, String avatarKey,
                                         ClientInfo client) {
        var session = codeService.code2Session(code);
        var login = identityFacade.loginOrCreate(session.openid(), session.unionid(),
                nickname, avatarKey);
        if (!login.active()) {
            throw new BusinessException(ErrorCode.AUTH_ACCOUNT_DISABLED);
        }
        var tokens = identityFacade.issueTokens(login.userId(), client);
        var view = identityFacade.userView(login.userId());
        return new WechatLoginResult(tokens, view, login.created());
    }

    /** 已登录网页用户绑定 openid：幂等；openid 已属其他账号/换绑由 M1 抛业务异常。 */
    public WechatIdentityFacade.WechatUserView bindByCode(String code, Long userId) {
        var session = codeService.code2Session(code);
        identityFacade.bind(userId, session.openid(), session.unionid());
        return identityFacade.userView(userId);
    }

    /**
     * 已登录网页用户凭扫码 openid 直接绑定（B18 bind-by-token 后半程）：
     * openid 属扫码自动账号时 M1 自动匿名化注销并移交（bindWechat 内建语义）。
     */
    public WechatIdentityFacade.WechatUserView bindByOpenid(String openid, Long userId) {
        identityFacade.bind(userId, openid, null);
        return identityFacade.userView(userId);
    }

    /**
     * 扫码临时账号把 openid 移交给已有正式博客账号（B18 bind-migrate）：
     * M1 侧完成账密校验（复用登录防爆破）、匿名化注销、openid 移交、评论归属迁移与令牌签发。
     */
    public WechatIdentityFacade.BindMigrated bindMigrate(Long currentUserId, String targetUsername,
                                                         String rawPassword, String captchaId,
                                                         String captchaCode, ClientInfo client) {
        return identityFacade.bindMigrate(currentUserId, targetUsername, rawPassword,
                captchaId, captchaCode, client);
    }
}
