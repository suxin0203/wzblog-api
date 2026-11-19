package com.wzblog.common.web;

import com.wzblog.common.security.Role;

/**
 * 已认证用户 principal；只从 SecurityContext 获取，不接受请求体/查询参数传入。
 *
 * <p>【教学：为什么"当前用户"是一个只有两个字段的轻量 record】</p>
 * <ul>
 *   <li>它是 {@code JwtAuthenticationFilter} 校验通过后放进 SecurityContext 的主体，
 *       Controller 里用 {@code @AuthenticationPrincipal CurrentUser user} 直接拿到；
 *       未登录时为 null（游客），业务代码到处可见 {@code user != null} 的可见性分支。</li>
 *   <li><b>刻意只带 id + role</b>：过滤器每个请求都要构建它，越轻越好；昵称头像等
 *       富信息按需查库/查缓存，避免"principal 里塞了过期数据"的一致性问题。</li>
 *   <li><b>类注释里那句"不接受请求体/查询参数传入"是安全红线</b>：身份必须来自
 *       服务端校验过的 token，凡是"前端说我是谁就是谁"（如 body 里传 userId）的写法
 *       都是越权漏洞（IDOR）。看到 Controller 方法同时接收 CurrentUser 和请求体里的
 *       userId 字段时，永远以 CurrentUser 为准。</li>
 * </ul>
 */
public record CurrentUser(Long id, Role role) {

    /** 管理员判断的便捷方法：让调用方写 user.isAdmin() 而不是到处比较枚举。 */
    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
