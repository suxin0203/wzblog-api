package com.wzblog.config;

import com.wzblog.common.profile.TitleBrief;
import com.wzblog.common.profile.TitleLookup;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/**
 * M4 未实现前的称号降级实现：始终返回空，资料接口的 activeTitle 为 null。
 */
@Configuration
public class ProfileConfig {

    @Bean
    @ConditionalOnMissingBean(TitleLookup.class)
    public TitleLookup noopTitleLookup() {
        return new TitleLookup() {
            @Override
            public Optional<TitleBrief> findById(Long titleId) {
                return Optional.empty();
            }

            @Override
            public Map<Long, TitleBrief> findByIds(Collection<Long> titleIds) {
                return Map.of();
            }
        };
    }
}