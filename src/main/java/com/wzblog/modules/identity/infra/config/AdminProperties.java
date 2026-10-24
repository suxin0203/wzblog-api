package com.wzblog.modules.identity.infra.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 管理员引导配置，映射 WZBLOG_ADMIN_USERNAME/PASSWORD/EMAIL。
 */
@Component
@ConfigurationProperties(prefix = "wzblog.admin")
public class AdminProperties {

    private String username;
    private String password;
    private String email;

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }
}