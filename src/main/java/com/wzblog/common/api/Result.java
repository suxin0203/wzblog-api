package com.wzblog.common.api;

import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.TraceId;

public record Result<T>(String code, String message, T data, String traceId) {

    public static <T> Result<T> ok(T data) {
        return new Result<>(ErrorCode.OK.code(), "success", data, TraceId.current());
    }

    public static Result<Void> ok() {
        return new Result<>(ErrorCode.OK.code(), "success", null, TraceId.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return fail(errorCode, errorCode.defaultMessage());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message) {
        return new Result<>(errorCode.code(), message, null, TraceId.current());
    }

    public static <T> Result<T> fail(ErrorCode errorCode, String message, T data) {
        return new Result<>(errorCode.code(), message, data, TraceId.current());
    }
}