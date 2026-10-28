package com.wzblog.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 元信息：Swagger UI 的 Authorize 按钮使用 Bearer JWT。
 * 路由与 Schema 仍由 springdoc 自动扫描，不在 Controller 上堆 @Operation。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI wzblogOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("文栈博客 API")
                        .version("v1")
                        .description("统一前缀 /api/v1；响应壳 {code,message,data,traceId}；"
                                + "ID 为字符串；鉴权使用 Authorization: Bearer <accessToken>。"))
                .components(new Components().addSecuritySchemes(SECURITY_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME));
    }
}
