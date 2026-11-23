package com.wzblog.common.security;

import com.baomidou.mybatisplus.annotation.EnumValue;

/**
 * 角色（00 §6.1）：{@code USER < EDITOR < ADMIN}。
 *
 * <p>【教学：枚举建模角色的三个要点】（详解见 monorepo 根 docs/学习文档/07）</p>
 * <ul>
 *   <li><b>扁平三层而非 RBAC 多表</b>：博客量级用"角色枚举 + 角色层级"足够，
 *       引入 role/permission 关联表是过度设计 —— 技术选型匹配业务复杂度；</li>
 *   <li><b>{@code @EnumValue} 注解</b>：MyBatis-Plus 的枚举映射标记，数据库存字符串
 *       "ADMIN"（VARCHAR+CHECK 约束可校验），Java 侧拿到类型安全的枚举 —— 比存 1/2/3
 *       强在 DBA 查库直接可读；</li>
 *   <li><b>{@link #authority()} 方法</b>：Spring Security 约定权限名要带 {@code ROLE_}
 *       前缀（hasRole('ADMIN') 实际匹配 ROLE_ADMIN）。把拼接收口在枚举方法里，
 *       全项目不会出现有的地方写了前缀、有的地方漏了的经典 bug。</li>
 * </ul>
 * <p>角色层级的继承关系（ADMIN 自动拥有 EDITOR 权限）由
 * {@code config/RoleHierarchyConfig} 的 RoleHierarchy Bean 声明。</p>
 */
public enum Role {

    USER("USER"),       // 普通注册用户：读、评论、点赞、积分
    EDITOR("EDITOR"),   // 受邀作者：在 USER 基础上可写文章、治理自己文章的评论
    ADMIN("ADMIN");     // 站主：全部权限（用户管理、全局治理、看板）

    /** MyBatis-Plus 枚举落库值（users.role 列存该字符串）。 */
    @EnumValue
    private final String value;

    Role(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }

    /** Spring Security authority，统一 {@code ROLE_} 前缀。 */
    public String authority() {
        return "ROLE_" + name();
    }
}
