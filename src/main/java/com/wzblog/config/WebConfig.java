package com.wzblog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // 业务 REST 统一挂 /api/v1；@InfrastructureRoute（本地媒体等基础设施路由）不加前缀
        configurer.addPathPrefix("/api/v1", HandlerTypePredicate.forAnnotation(
                        org.springframework.web.bind.annotation.RestController.class)
                .and(HandlerTypePredicate.forAnnotation(
                        com.wzblog.common.web.InfrastructureRoute.class).negate()));
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS")
                .allowedHeaders("*")
                .exposedHeaders("X-Trace-Id", "Retry-After")
                .allowCredentials(false)
                .maxAge(3600);
    }
}