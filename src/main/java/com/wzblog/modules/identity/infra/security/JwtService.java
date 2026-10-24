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
 */
@Component
public class JwtService {

    public static final String ROLE_CLAIM = "role";

    private final AuthProperties properties;
    private final SecretKey key;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        byte[] secret = properties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        if (secret.length < 32) {
            throw new IllegalStateException("wzblog.auth.jwt-secret must be at least 256 bits");
        }
        this.key = Keys.hmacShaKeyFor(secret);
    }

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