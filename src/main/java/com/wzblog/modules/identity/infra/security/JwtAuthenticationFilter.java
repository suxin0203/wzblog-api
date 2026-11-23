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
 *
 * <p>【教学：自定义安全过滤器的三个关键设计】（详解见 monorepo 根 docs/学习文档/04 与 07）</p>
 * <ol>
 *   <li><b>匿名放行而非拒绝</b>：没带 token 的请求直接放行走完过滤器链 ——
 *       接口是否公开由 SecurityConfig 的权限矩阵决定。游客可访问首页、
 *       受保护接口则因"无认证信息"被 EntryPoint 转 401。</li>
 *   <li><b>token 无效不直接 401</b>（初学者最容易写错的地方）：失败时把 ErrorCode
 *       记到 request attribute 后<b>继续放行</b>。若直接 401，用户登录过期后连公开接口
 *       （首页）都打不开 —— 因为浏览器仍会带着过期 token 请求。公开接口照常响应，
 *       受保护接口走到授权检查时才由 EntryPoint 读出 attribute 输出精确的 401。</li>
 *   <li><b>黑名单 + 回库双重校验</b>：JWT 验签通过只说明"签发时有效"，不代表"现在有效"。
 *       登出/改密后 jti 进 Redis 黑名单（JWT 本身无法撤销，这是补救）；再回库确认
 *       用户未注销未停用 —— 管理员封号下一个请求就生效。</li>
 * </ol>
 * <p>本类由 SecurityConfig 用 {@code addFilterBefore(new JwtAuthenticationFilter(...),
 * UsernamePasswordAuthenticationFilter.class)} 手工挂进过滤器链（而不是 @Component）——
 * 保持对装配位置与依赖的完全掌控。</p>
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
            // 认证成功：构建 Spring Security 的认证对象放入上下文。
            // principal = CurrentUser（Controller 里 @AuthenticationPrincipal 取的就是它）；
            // credentials 传 null（JWT 场景不放凭证本体）；authorities = 角色权限（ROLE_USER 等），
            // 之后的 hasRole/@PreAuthorize 判断都基于这个 authority 列表。
            CurrentUser principal = new CurrentUser(user.getId(), user.getRole());
            UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                    principal, null, List.of(new SimpleGrantedAuthority(user.getRole().authority())));
            // details 挂载 jti 与过期时间：登出接口凭它把 jti 写入黑名单
            authentication.setDetails(new AccessTokenDetails(claims.jti(), claims.expiresAt()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            filterChain.doFilter(request, response);
        } catch (com.wzblog.common.error.BusinessException ex) {
            // Public endpoints must stay readable with a stale/expired token; protected ones
            // turn this attribute into a precise 401 via the AuthenticationEntryPoint.
            // token 验签/过期/黑名单失败：不中断请求（见类注释设计②），记下精确错误码后放行，
            // 由受保护路径的 EntryPoint 把它转成 401；公开路径则无视坏 token 正常响应。
            request.setAttribute(SecurityErrorWriter.AUTH_ERROR_ATTRIBUTE, ex.errorCode());
            filterChain.doFilter(request, response);
        }
    }
}