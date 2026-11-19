package com.wzblog.common.error;

/**
 * 业务异常 —— 全项目唯一的业务失败信号（教学详解见 docs/学习文档/05）。
 *
 * <p>【它是什么】携带 {@link ErrorCode} 的运行时异常。业务代码里"积分不足""文章不存在"
 * 这类<b>预期内的失败</b>，直接一行抛出：</p>
 * <pre>{@code
 * if (balance < price) throw new BusinessException(ErrorCode.POINTS_INSUFFICIENT);
 * }</pre>
 *
 * <p>【为什么继承 RuntimeException（非受检）而不是 Exception】业务失败被本项目视为
 * 正常控制流的一部分（用户输错密码很正常），不应强迫每个方法签名声明 throws、
 * 每层调用者写 try-catch 样板。让异常自己"向上飞"，直到
 * {@link GlobalExceptionHandler} 统一接住翻译成 HTTP 响应 —— Controller 因此保持干净。
 * 经验法则：框架/基础设施错误用受检异常逼人处理，业务规则失败用运行时异常上抛。</p>
 *
 * <p>【三个载荷字段的设计】</p>
 * <ul>
 *   <li>{@code errorCode}：决定 HTTP 状态码与业务码，必填（错误码先登记后使用）；</li>
 *   <li>{@code retryAfterSeconds}：仅 429 限流场景使用，会被处理器写成 Retry-After 响应头；</li>
 *   <li>{@code data}：结构化附加数据（如 FILE_IN_USE 时返回引用位置清单），随 Result.data 给前端。</li>
 * </ul>
 */
public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;
    private final Long retryAfterSeconds;
    private final Object data;

    /** 最常用：只给错误码，使用登记时的默认中文文案。 */
    public BusinessException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), null, null);
    }

    /** 定制文案：需要带业务上下文时用（如"用户名已被占用：xxx"）。 */
    public BusinessException(ErrorCode errorCode, String message) {
        this(errorCode, message, null, null);
    }

    /** 限流场景：附带建议客户端等待的秒数（Retry-After 头）。 */
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

    /** 本异常对应的错误码（含 HTTP 状态与默认文案）。 */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 429 场景的 Retry-After 秒数，可为 null。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 结构化附加数据，可为 null。 */
    public Object data() {
        return data;
    }
}
