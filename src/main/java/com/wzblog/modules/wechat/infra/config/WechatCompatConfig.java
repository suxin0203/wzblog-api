package com.wzblog.modules.wechat.infra.config;

import com.wzblog.common.web.LegacyTimestampFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 旧协议兼容层装配：时间戳防重放仅作用于旧路径（小程序每请求带 X-Request-Time）。
 */
@Configuration
public class WechatCompatConfig {

    @Bean
    public FilterRegistrationBean<LegacyTimestampFilter> legacyTimestampFilter(
            @org.springframework.beans.factory.annotation.Value(
                    "${wzblog.wechat.timestamp-max-skew-seconds:300}") long skewSeconds) {
        FilterRegistrationBean<LegacyTimestampFilter> registration =
                new FilterRegistrationBean<>(new LegacyTimestampFilter(skewSeconds));
        registration.addUrlPatterns("/qr-login/*", "/miniapp/*", "/wechat/*", "/users/*");
        registration.setOrder(30);
        return registration;
    }
}
