package com.wzblog.common.web;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 客户端信息（IP + User-Agent）。代理头仅在请求来自本机/环回地址时采信。
 *
 * <p>【教学：获取客户端 IP 的正确姿势】（详解见 docs/学习文档/13）</p>
 * <p>{@code request.getRemoteAddr()} 拿到的是<b>直连者</b>的 IP —— 生产环境部署了
 * nginx 反向代理后，它永远是 127.0.0.1，真实 IP 藏在 {@code X-Forwarded-For} 头里。</p>
 *
 * <p><b>但 XFF 不能无条件信任</b>：它只是个普通请求头，客户端想伪造就伪造 ——
 * 直接拿它做限流键，攻击者每次换个假 IP 就绕过了限流。本类的策略：</p>
 * <ul>
 *   <li>仅当直连 IP 是环回地址（说明前置确有自家反向代理）时才采信 XFF；</li>
 *   <li>XFF 形如 {@code 客户端IP, 代理1, 代理2}，取<b>第一跳</b>（最原始的客户端）；</li>
 *   <li>直连是外网 IP 时（没经过代理的开发场景），XFF 一律无视。</li>
 * </ul>
 * <p>用途：登录审计、防爆破限流、评论风控留痕。IP 截 45 字符（IPv6 最长）、
 * UA 截 255 —— <b>入库前的长度截断</b>防止恶意超长头撑爆数据库行。</p>
 */
public record ClientInfo(String ip, String userAgent) {

    private static final int IP_MAX = 45;    // IPv6 文本形式最长 45 字符
    private static final int UA_MAX = 255;   // 与数据库 user_agent 列宽一致

    /** 从 Servlet 请求提取（含受信代理头处理与截断）。 */
    public static ClientInfo from(HttpServletRequest request) {
        String remote = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        String ip = remote;
        if (forwarded != null && !forwarded.isBlank() && isTrustedProxy(remote)) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                ip = first;
            }
        }
        return new ClientInfo(truncate(ip, IP_MAX), truncate(request.getHeader("User-Agent"), UA_MAX));
    }

    /** 已知值的直接构造（测试/内部调用用），同样做截断防御。 */
    public static ClientInfo of(String ip, String userAgent) {
        return new ClientInfo(truncate(ip, IP_MAX), truncate(userAgent, UA_MAX));
    }

    /** 只有直连者是本机回环（自家 nginx 在前面）时，X-Forwarded-For 才可信。 */
    private static boolean isTrustedProxy(String remote) {
        return "127.0.0.1".equals(remote) || "::1".equals(remote) || "0:0:0:0:0:0:0:1".equals(remote);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
