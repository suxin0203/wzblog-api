package com.wzblog.modules.identity.infra.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.api.Result;
import com.wzblog.common.error.ErrorCode;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 过滤器链内输出统一 JSON 壳（不走 @RestControllerAdvice）。
 */
public final class SecurityErrorWriter {

    public static final String AUTH_ERROR_ATTRIBUTE = "wzblog.authError";

    private SecurityErrorWriter() {
    }

    public static void write(HttpServletResponse response, ObjectMapper objectMapper,
                             ErrorCode errorCode) throws IOException {
        response.setStatus(errorCode.httpStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getWriter(), Result.fail(errorCode));
    }
}