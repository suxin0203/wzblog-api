package com.wzblog.common.web;

import com.wzblog.common.security.Role;

/**
 * 已认证用户 principal；只从 SecurityContext 获取，不接受请求体/查询参数传入。
 */
public record CurrentUser(Long id, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}