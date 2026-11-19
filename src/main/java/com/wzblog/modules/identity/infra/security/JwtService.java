package com.wzblog.modules.identity.infra.security;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.security.Role;
import com.wzblog.modules.identity.infra.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Access Token 签发与校验：HS256、15 分钟，claims 仅 sub/role/jti/iss/aud/iat/exp。
 *
 * <p>【教学：JWT 服务的要点】（详解见 docs/学习文档/07）</p>
 * <ul>
 *   <li><b>claims 里放什么</b>：只放"还原身份所需的最小集"—— sub(userId)、role、
 *       jti（唯一号，登出黑名单的键）。JWT 前两段只是 Base64，<b>谁都能解码看</b>，
 *       所以绝不放密码/手机号等敏感信息；签名只保证"不可伪造"，不保证"不可读"。</li>
 *   <li><b>iss/aud 校验</b>：签发者(wzblog)与受众(wzblog-web)都验 ——
 *       防止"别处签发的令牌"混进本系统。</li>
 *   <li><b>构造器 fail-fast</b>：密钥不足 256 位直接启动失败（IllegalStateException），
 *       而不是运行到第一次签发时才炸 —— 配置错误在启动一刻暴露，这是
 *       "fail-fast"原则的标准落地。</li>
 *   <li><b>过期与无效分开报</b>：parse 时 ExpiredJwtException → AUTH_TOKEN_EXPIRED
 *       （提示重新登录），其他验签失败 → AUTH_TOKEN_INVALID（token 被篡改/格式坏）。
 *       前端对两种错误的处理不同（前者可静默刷新，后者必须踢回登录页）。</li>
 * </ul>
 */
@Component
public class JwtService {

    /** 自定义 claim 名：角色。 */
    public static final String ROLE_CLAIM = "role";

    private final AuthProperties properties;
    private final SecretKey key;

    /** 构造器里完成密钥校验与 SecretKey 构建（fail-fast，见类注释）。 */
    public JwtService(AuthProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            // HS256 安全下限 256bit：短密钥可被暴力破解，宁可启动失败也不带病运行
            throw new IllegalStateException("wzblog.auth.jwt-secret must be at least 256 bits");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

    /**
     * 签发 access token（全项目唯一签发口）。
     * 双令牌体系里它只管"短命门禁卡"（15 分钟）；换新的长命 refresh token
     * 由 AuthAppService.issueTokens 统一编排（登录/刷新/微信登录都汇流到那里）。
     */
    public IssuedAccessToken issue(Long userId, Role role) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.getAccessTtl());
        String jti = UUID.randomUUID().toString();
        String token = Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(ROLE_CLAIM, role.name())
                .id(jti)
                .issuer(properties.getIssuer())
                .audience().add(properties.getAudience()).and()
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, jti, expiresAt, ttlSeconds(properties.getAccessTtl()));
    }

    /**
     * 校验签名/iss/aud/exp，返回 claims；失败抛业务异常（AUTH_TOKEN_EXPIRED / AUTH_TOKEN_INVALID）。
     */
    public AccessTokenClaims parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .requireIssuer(properties.getIssuer())
                    .requireAudience(properties.getAudience())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long userId;
            try {
                userId = Long.valueOf(claims.getSubject());
            } catch (RuntimeException ex) {
                throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
            }
            Role role = Role.USER;
            String roleClaim = claims.get(ROLE_CLAIM, String.class);
            if (roleClaim != null) {
                try {
                    role = Role.valueOf(roleClaim);
                } catch (IllegalArgumentException ignored) {
                    role = Role.USER;
                }
            }
            return new AccessTokenClaims(userId, role, claims.getId(),
                    claims.getExpiration().toInstant());
        } catch (ExpiredJwtException ex) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_EXPIRED);
        } catch (JwtException | IllegalArgumentException ex) {
            throw new BusinessException(ErrorCode.AUTH_TOKEN_INVALID);
        }
    }

    public long accessTtlSeconds() {
        return ttlSeconds(properties.getAccessTtl());
    }

    public long refreshTtlSeconds() {
        return ttlSeconds(properties.getRefreshTtl());
    }

    public static long ttlSeconds(Duration duration) {
        return Math.max(1L, duration.toSeconds());
    }

    public record IssuedAccessToken(String value, String jti, Instant expiresAt, long expiresIn) {
    }

    public record AccessTokenClaims(Long userId, Role role, String jti, Instant expiresAt) {
    }
}