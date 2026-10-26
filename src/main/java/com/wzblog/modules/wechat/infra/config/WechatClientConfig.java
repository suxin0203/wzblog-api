package com.wzblog.modules.wechat.infra.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.modules.wechat.infra.wechat.MockWeChatApiClient;
import com.wzblog.modules.wechat.infra.wechat.RealWeChatApiClient;
import com.wzblog.modules.wechat.infra.wechat.WeChatApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.client.RestClient;

/**
 * 上游装配：provider=auto 时凭据齐备走 real、缺失走 mock（本地/CI 全链路可用）。
 */
@Configuration
public class WechatClientConfig {

    private static final Logger log = LoggerFactory.getLogger(WechatClientConfig.class);

    @Bean
    public WeChatApiClient weChatApiClient(WechatProperties properties,
                                           StringRedisTemplate redis, ObjectMapper objectMapper,
                                           RestClient.Builder restClientBuilder) {
        String provider = properties.effectiveProvider();
        log.info("微信上游实现：provider={}（appId 配置：{}）", provider,
                properties.credentialsPresent() ? "已配置" : "缺失");
        if ("real".equals(provider)) {
            return new RealWeChatApiClient(properties, redis, objectMapper, restClientBuilder);
        }
        return new MockWeChatApiClient(properties);
    }
}
