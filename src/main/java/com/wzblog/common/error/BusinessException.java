package com.wzblog.common.error;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Long retryAfterSeconds;
    private final Object data;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    public BusinessException(ErrorCode errorCode, String message, Long retryAfterSeconds) {
        this(errorCode, message, retryAfterSeconds, null);
    }

    /** 附带结构化数据（如 FILE_IN_USE 的引用位置清单），随 Result.data 返回调用方。 */
    public BusinessException(ErrorCode errorCode, String message, Long retryAfterSeconds, Object data) {
        super(message);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.data = data;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 429 场景的 Retry-After 秒数，可为 null。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    public Object data() {
        return data;
    }
}