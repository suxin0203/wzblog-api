package com.wzblog.modules.identity.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.modules.identity.domain.User;
import com.wzblog.modules.identity.infra.mapper.UserMapper;
import com.wzblog.modules.identity.infra.redis.TokenStateStore;
import com.wzblog.common.security.AccessTokenDetails;
import com.wzblog.common.web.CurrentUser;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Bearer Token 过滤器：校验签名/过期/黑名单，并回库校验用户存在且 ACTIVE（M1 §3.1）。
 * 未携带凭证时放行为匿名，受保护路径由 EntryPoint 输出 401。
 */
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserMapper userMapper;
    private final TokenStateStore tokenStateStore;
    private final ObjectMapper objectMapper;

    public JwtAuthenticationFilter(JwtService jwtService, UserMapper userMapper,
                                   TokenStateStore tokenStateStore, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.userMapper = userMapper;
        this.tokenStateStore = tokenStateStore;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HEADER);
        if (header == null || !header.startsWith(PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }
        String token = header.substring(PREFIX.length()).trim();
        if (token.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        try {
            JwtService.AccessTokenClaims claims = jwtService.parse(token);
            if (tokenStateStore.isBlacklisted(claims.jti())) {
                request.setAttribute(SecurityErrorWriter.AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
                filterChain.doFilter(request, response);
                return;
            }
            User user = userMapper.selectById(claims.userId());
            if (user == null || user.isDeleted()) {
                request.setAttribute(SecurityErrorWriter.AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_TOKEN_INVALID);
                filterChain.doFilter(request, response);
                return;
            }
            if (user.getStatus() != com.wzblog.modules.identity.domain.UserStatus.ACTIVE) {
                request.setAttribute(SecurityErrorWriter.AUTH_ERROR_ATTRIBUTE, ErrorCode.AUTH_ACCOUNT_DISABLED);
                filterChain.doFilter(request, response);
                return;
            }
            CurrentUser principal = new CurrentUser(user.getId(), user.getRole());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(user.getRole().authority())));
            authentication.setDetails(new AccessTokenDetails(claims.jti(), claims.expiresAt()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (com.wzblog.common.error.BusinessException ex) {
            // Public endpoints must stay readable with a stale/expired token; protected ones
            // turn this attribute into a precise 401 via the AuthenticationEntryPoint.
            request.setAttribute(SecurityErrorWriter.AUTH_ERROR_ATTRIBUTE, ex.errorCode());
            filterChain.doFilter(request, response);
        }
    }
}