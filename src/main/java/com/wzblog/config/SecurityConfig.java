package com.wzblog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import com.wzblog.modules.identity.infra.redis.TokenStateStore;
import com.wzblog.modules.identity.infra.security.JwtAuthenticationFilter;
import com.wzblog.modules.identity.infra.security.JwtService;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.NullSecurityContextRepository;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * 无状态安全链：JSON 401/403 + Bearer Token 过滤器 + 路由级角色控制。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    private static final String[] PUBLIC_PATHS = {
            "/api/v1/ping",
            "/actuator/health",
            "/actuator/health/**",
            "/actuator/info",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/error"
    };

    private static final String[] PUBLIC_AUTH_PATHS = {
            "/api/v1/auth/captcha",
            "/api/v1/auth/register",
            "/api/v1/auth/login",
            "/api/v1/auth/refresh",
            "/api/v1/auth/password/forgot/**"
    };

    @Bean
    public SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtService jwtService,
            UserMapper userMapper,
            TokenStateStore tokenStateStore,
            ObjectMapper objectMapper,
            @Qualifier("jsonAuthenticationEntryPoint") AuthenticationEntryPoint authenticationEntryPoint,
            @Qualifier("jsonAccessDeniedHandler") AccessDeniedHandler accessDeniedHandler,
            ObjectProvider<RequestMappingHandlerMapping> handlerMappingProvider) throws Exception {
        http.csrf(AbstractHttpConfigurer::disable)
                .cors(Customizer.withDefaults())
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .securityContext(context -> context.securityContextRepository(new NullSecurityContextRepository()))
                .exceptionHandling(handling -> handling
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        .requestMatchers(PUBLIC_AUTH_PATHS).permitAll()
                        .requestMatchers("/api/v1/users/*/profile").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/articles", "/api/v1/articles/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/categories/**", "/api/v1/tags/**").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/articles/*/view").permitAll()
                        // 未映射的路径放行，交由 MVC 输出 404（避免未知路径被 401 掩盖）
                        .requestMatchers(unmappedRequest(handlerMappingProvider)).permitAll()
                        .requestMatchers("/api/v1/admin/users/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/admin/**").hasAnyRole("EDITOR", "ADMIN")
                        .requestMatchers("/actuator/prometheus").hasRole("ADMIN")
                        .anyRequest().authenticated())
                .addFilterBefore(new JwtAuthenticationFilter(jwtService, userMapper, tokenStateStore, objectMapper),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }

    /**
     * 只匹配「没有任何 MVC handler」的请求，用于把未知路径交给 404 处理器。
     */
    private static RequestMatcher unmappedRequest(ObjectProvider<RequestMappingHandlerMapping> provider) {
        return request -> {
            RequestMappingHandlerMapping mapping = provider.orderedStream()
                    .filter(candidate -> "RequestMappingHandlerMapping".equals(candidate.getClass().getSimpleName()))
                    .findFirst()
                    .orElse(null);
            if (mapping == null) {
                return false;
            }
            try {
                return mapping.getHandler(request) == null;
            } catch (Exception ex) {
                return false;
            }
        };
    }
}