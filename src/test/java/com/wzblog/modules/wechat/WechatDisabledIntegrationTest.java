package com.wzblog.modules.wechat;

import com.wzblog.support.IdentityTestSupport;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 模块关闭守卫（docs/16 §4）：provider=auto 且凭据缺失 → 微信功能一律 503 WECHAT_NOT_ENABLED，
 * 密码登录不受影响。防止生产忘配凭据时静默走 Mock——假 code 可登录建号（P1 安全隐患）。
 * 本地/CI 确定性上游必须显式 provider=mock（application-test.yml 即如此，不受本守卫影响）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "wzblog.wechat.provider=auto",
        "wzblog.wechat.app-id=",
        "wzblog.wechat.app-secret="
})
class WechatDisabledIntegrationTest extends IdentityTestSupport {

    @Test
    void moduleClosedWithoutCredentialsReturns503Everywhere() {
        // 新契约：创建会话 / code 登录 → 503
        var create = post("/api/v1/auth/qr/sessions", Map.of(), null);
        assertThat(create.status()).isEqualTo(503);
        assertThat(create.code()).isEqualTo("WECHAT_NOT_ENABLED");

        var login = post("/api/v1/auth/wechat/login", Map.of("code", "any-code"), null);
        assertThat(login.status()).isEqualTo(503);
        assertThat(login.code()).isEqualTo("WECHAT_NOT_ENABLED");

        // 旧协议兼容层：code 换 openid → 旧壳（HTTP 200 + 数字 code:0 + errcode:503），
        // request.js 判定习惯不允许直接抛 HTTP 503（LegacyWechatCompatController.openid 的 catch 分支）
        var openid = get("/wechat/openid/any-code", null);
        assertThat(openid.status()).isEqualTo(200);
        assertThat(openid.body().path("code").asInt()).isZero();
        assertThat(openid.body().path("errcode").asInt()).isEqualTo(503);

        // 密码登录不受影响（docs/16 §4 承诺）
        var captcha = get("/api/v1/auth/captcha", null);
        assertThat(captcha.code()).isEqualTo("OK");
    }
}
