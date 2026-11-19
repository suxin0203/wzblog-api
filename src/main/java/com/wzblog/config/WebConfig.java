package com.wzblog.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.method.HandlerTypePredicate;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.PathMatchConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 定制：统一 /api/v1 路径前缀 + 全局 CORS 策略（教学详解见 docs/学习文档/13）。
 *
 * <p>【教学：WebMvcConfigurer 是什么】Spring MVC 官方预留的"定制钩子"接口，
 * 实现 @Configuration 类后覆写对应方法即可微调框架行为 —— 比 @EnableWebMvc
 * 全量接管安全（后者会关掉 Boot 的自动配置）。</p>
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    /**
     * 给本项目所有 @RestController 统一挂 /api/v1 前缀。
     * 好处：Controller 上只写业务路径（@RequestMapping("/articles")），
     * 版本升级（/api/v2）只改这一处。
     * 三个过滤条件各自的原因：
     * <ul>
     *   <li>限定 com.wzblog 包 —— 避免把 springdoc 的 /v3/api-docs 也加上前缀；</li>
     *   <li>只对 @RestController 生效 —— 排除其他类型的 handler；</li>
     *   <li>排除 @InfrastructureRoute 标注的类 —— 本地媒体路由（/media/**、
     *       /media-upload/**）是基础设施路径，不参与 API 版本前缀。</li>
     * </ul>
     */
    @Override
    public void configurePathMatch(PathMatchConfigurer configurer) {
        // 业务 REST 统一挂 /api/v1；@InfrastructureRoute（本地媒体等基础设施路由）不加前缀。
        // 限定 com.wzblog，避免把 springdoc 自身的 /v3/api-docs 也加上前缀。
        configurer.addPathPrefix("/api/v1",
                HandlerTypePredicate.forBasePackage("com.wzblog")
                        .and(HandlerTypePredicate.forAnnotation(
                                org.springframework.web.bind.annotation.RestController.class))
                        .and(HandlerTypePredicate.forAnnotation(
                                com.wzblog.common.web.InfrastructureRoute.class).negate()));
    }

    /**
     * 全局 CORS：允许跨域访问 /api/**。
     * 场景：前端 dev server (5173) 调后端 (8021) 是跨域，浏览器会先发 OPTIONS 预检。
     * 细节：
     * <ul>
     *   <li>{@code exposedHeaders} 最易漏：默认前端 JS 读不到自定义响应头，
     *       X-Trace-Id（报障对账）和 Retry-After（限流提示）必须显式暴露；</li>
     *   <li>{@code maxAge(3600)}：预检结果缓存 1 小时，减少 OPTIONS 往返；</li>
     *   <li>开发期更省事的替代方案是 Vite 代理（/api 转发到 8021，全程同源），
     *       生产由 nginx 同域分发 —— CORS 是"服务端声明允许跨域"的机制，两种路线按环境选。</li>
     * </ul>
     */
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
