package com.wzblog.modules.identity.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 角色/权限不足：403 {@code AUTH_FORBIDDEN}。
 */
@Component("jsonAccessDeniedHandler")
public class JsonAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    public JsonAccessDeniedHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        SecurityErrorWriter.write(response, objectMapper, ErrorCode.AUTH_FORBIDDEN);
    }
}