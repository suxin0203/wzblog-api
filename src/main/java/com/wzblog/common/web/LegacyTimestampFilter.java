package com.wzblog.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 旧协议时间戳防重放（兼容已上线小程序）：小程序每个请求带 X-Request-Time（毫秒）。
 * 旧系统容忍 30s；此处放宽到 5 分钟（只松不严，对抗手机时钟不准，不破坏小程序）。
 * 仅作用于兼容层路径（FilterRegistrationBean 指定 urlPatterns，见 WechatCompatConfig）。
 */
// 不加 @Component：仅经 WechatCompatConfig 的 FilterRegistrationBean 按旧路径注册，
// 避免被 Boot 自动挂到全局过滤链
public class LegacyTimestampFilter extends OncePerRequestFilter {

    private final long maxSkewMillis;

    public LegacyTimestampFilter(@Value("${wzblog.wechat.timestamp-max-skew-seconds:300}") long skewSeconds) {
        this.maxSkewMillis = skewSeconds * 1000;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("X-Request-Time");
        if (header != null && !header.isBlank()) {
            try {
                long clientTime = Long.parseLong(header.trim());
                long skew = Math.abs(System.currentTimeMillis() - clientTime);
                if (skew > maxSkewMillis) {
                    response.setStatus(200);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write(
                            "{\"code\":400,\"message\":\"请求已过期或时间偏差过大，请同步系统时间后重试\",\"data\":null}");
                    return;
                }
            } catch (NumberFormatException ignored) {
                // 非数字头不拦截（与旧系统一致：仅校验合法格式）
            }
        }
        chain.doFilter(request, response);
    }
}
