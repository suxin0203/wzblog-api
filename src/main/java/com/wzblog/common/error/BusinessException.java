package com.wzblog.common.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Long retryAfterSeconds;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Long retryAfterSeconds) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 429 场景的 Retry-After 秒数，可为 null。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}