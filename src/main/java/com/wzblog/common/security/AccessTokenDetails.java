package com.wzblog.common.security;

import java.time.Instant;

/**
 * 当前请求所用 access token 的附加信息（登出黑名单需要 jti 与剩余有效期）。
 *
 * <p>【教学：它挂在哪里、为什么存在】JWT 过滤器校验通过后，会把它
 * {@code authentication.setDetails(...)} 到 Spring Security 的 Authentication 对象上 ——
 * details 是"认证过程的附属信息"标准挂载点。</p>
 *
 * <p>登出流程需要它的两个字段：</p>
 * <ul>
 *   <li>{@code jti}（JWT ID）：登出时把它写入 Redis 黑名单 —— 这是"JWT 无法主动撤销"
 *       问题的补救方案（详见 docs/学习文档/07）；</li>
 *   <li>{@code expiresAt}：黑名单条目的 TTL 直接取令牌剩余寿命，令牌自然过期后
 *       黑名单条目也自动消失，不给 Redis 留垃圾。</li>
 * </ul>
 */
public record AccessTokenDetails(String jti, Instant expiresAt) {
}
