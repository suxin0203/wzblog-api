package com.wzblog.modules.wechat.app;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IP 脱敏纯单测（不起 Spring）：IPv4 保留前两段、IPv6 回环标注「本机」、
 * 公网 IPv6 保留前两组、X-Forwarded-For 取首段、空值兜底。
 * 背景：本地调试 getRemoteAddr 返回 IPv6 回环 0:0:0:0:0:0:0:1，旧截断逻辑输出 0:0:0:0:0:0:…。
 */
class QrSessionMaskIpTest {

    @Test
    void ipv4KeepsFirstTwoOctets() {
        assertThat(QrSessionService.maskIp("112.10.34.56")).isEqualTo("112.10.*.*");
        assertThat(QrSessionService.maskIp("127.0.0.1")).isEqualTo("127.0.*.*");
    }

    @Test
    void ipv6LoopbackShowsLocal() {
        // getRemoteAddr 对 ::1 的两种返回形态
        assertThat(QrSessionService.maskIp("0:0:0:0:0:0:0:1")).isEqualTo("本机");
        assertThat(QrSessionService.maskIp("::1")).isEqualTo("本机");
    }

    @Test
    void publicIpv6KeepsFirstTwoGroups() {
        assertThat(QrSessionService.maskIp("2408:8207:1234:abcd::1")).isEqualTo("2408:8207:…");
        assertThat(QrSessionService.maskIp("fe80:0:0:0:0:0:0:5")).isEqualTo("fe80:0:…");
    }

    @Test
    void forwardedForTakesFirstAndNullSafe() {
        assertThat(QrSessionService.maskIp("10.0.0.1, 172.16.0.1")).isEqualTo("10.0.*.*");
        assertThat(QrSessionService.maskIp(null)).isEmpty();
        assertThat(QrSessionService.maskIp("")).isEmpty();
        assertThat(QrSessionService.maskIp("  ")).isEmpty();
    }
}
