package com.wzblog.modules.identity.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 未携带任何凭证访问受保护路径：401 {@code AUTH_UNAUTHORIZED}。
 */
@Component("jsonAuthenticationEntryPoint")
public class JsonAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public JsonAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        Object attribute = request.getAttribute(SecurityErrorWriter.AUTH_ERROR_ATTRIBUTE);
        ErrorCode code = attribute instanceof ErrorCode errorCode ? errorCode : ErrorCode.AUTH_UNAUTHORIZED;
        SecurityErrorWriter.write(response, objectMapper, code);
    }
}