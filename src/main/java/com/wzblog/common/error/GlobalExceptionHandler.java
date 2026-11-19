package com.wzblog.common.error;

import com.wzblog.common.api.Result;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 全局异常处理器 —— 把任何一层抛出的异常统一翻译成 {@link Result} JSON（教学详解见 docs/学习文档/05）。
 *
 * <p>【教学：@RestControllerAdvice 是什么】"给所有 @Controller 织入公共行为"的 AOP 机制。
 * 效果：业务代码只管抛异常，HTTP 状态码、响应体格式、响应头、日志在这里集中收口 ——
 * 这就是本项目 Controller 里几乎看不到 try-catch 的原因。</p>
 *
 * <p>【阅读指南】本类是"异常分类学"：每个 @ExceptionHandler 负责一族异常。
 * 排错时按"响应长什么样"反查这里，就能知道异常从哪来。</p>
 *
 * <p>【兜底方法的安全铁律】handleUnknown 只把通用文案给前端，完整堆栈进日志 ——
 * 堆栈含类名/SQL/路径，泄漏到响应等于给攻击者画地图。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /**
     * 业务异常（本项目自己抛的，占绝大多数）。
     * HTTP 状态由 ErrorCode 登记值决定（如 POINTS_INSUFFICIENT→422、TOKEN_INVALID→401）；
     * 429 时补 Retry-After 头；附加数据随 Result.data 返回。
     */
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<Result<Object>> handleBusiness(BusinessException ex) {
        ErrorCode code = ex.errorCode();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(code.httpStatus());
        if (ex.retryAfterSeconds() != null) {
            builder.header("Retry-After", String.valueOf(ex.retryAfterSeconds()));
        }
        return builder.body(Result.fail(code, ex.getMessage(), ex.data()));
    }

    /**
     * 方法级 {@code @PreAuthorize} 拒绝发生在 MVC 内，需在此转换为 JSON 403。
     * （注：路由级拒绝发生在过滤器链，由 JsonAccessDeniedHandler 处理 —— 两处分工。）
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(403)
                .body(Result.fail(ErrorCode.AUTH_FORBIDDEN, ErrorCode.AUTH_FORBIDDEN.defaultMessage()));
    }

    /** Spring Security 的认证异常（未登录访问受保护资源等）→ 401 JSON。 */
    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Result<Object>> handleAuthentication(
            org.springframework.security.core.AuthenticationException ex) {
        return ResponseEntity.status(401)
                .body(Result.fail(ErrorCode.AUTH_UNAUTHORIZED, ErrorCode.AUTH_UNAUTHORIZED.defaultMessage()));
    }

    /** @RequestBody + @Valid 校验失败（DTO 字段上的 @NotBlank 等不满足）。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Result<Object>> handleValidation(MethodArgumentNotValidException ex) {
        return validationFailure(ex.getBindingResult());
    }

    /** 表单/查询对象绑定校验（@ModelAttribute + @Valid）。 */
    @ExceptionHandler(org.springframework.validation.BindException.class)
    public ResponseEntity<Result<Object>> handleBind(org.springframework.validation.BindException ex) {
        return validationFailure(ex.getBindingResult());
    }

    /** 方法参数级约束（@Validated + @RequestParam 约束），与 body 校验同构。 */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Result<Object>> handleConstraintViolation(ConstraintViolationException ex) {
        Map<String, String> errors = new LinkedHashMap<>();
        ex.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath() == null ? "" : violation.getPropertyPath().toString();
            // propertyPath 形如 "method.paramName"，截取最后一段作为字段名
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            errors.putIfAbsent(field, violation.getMessage());
        });
        return badRequestWithErrors(errors);
    }

    /**
     * 校验失败统一出口：产出 {@code data.errors = {字段名: 错误信息}} 的结构化 400。
     * 前端拿到字段级错误后可以直接在对应输入框标红 —— 比一句"参数错误"体验好得多。
     * putIfAbsent 保留每个字段的第一条错误（一个字段多条约束时只报最关键的第一条）。
     */
    private ResponseEntity<Result<Object>> validationFailure(
            org.springframework.validation.BindingResult bindingResult) {
        Map<String, String> errors = new LinkedHashMap<>();
        bindingResult.getFieldErrors()
                .forEach(error -> errors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        bindingResult.getGlobalErrors()
                .forEach(error -> errors.putIfAbsent("_global", error.getDefaultMessage()));
        return badRequestWithErrors(errors);
    }

    private ResponseEntity<Result<Object>> badRequestWithErrors(Map<String, String> errors) {
        Map<String, Object> data = Map.of("errors", errors);
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.COMMON_VALIDATION_ERROR, "参数校验失败", data));
    }

    /**
     * "请求本身没法解析"三兄弟 → 统一 400：
     * JSON 缺引号/多逗号、?id=abc 传给 Long 参数、必填 query 参数缺失。
     */
    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MethodArgumentTypeMismatchException.class,
            MissingServletRequestParameterException.class
    })
    public ResponseEntity<Result<Object>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.COMMON_VALIDATION_ERROR, "请求参数格式错误"));
    }

    /** multipart 超过 spring.servlet.multipart 上限（含非媒体路由的大 body）：转业务码而非 500。 */
    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Result<Object>> handleMaxUpload(Exception ex) {
        return ResponseEntity.badRequest()
                .body(Result.fail(ErrorCode.FILE_SIZE_EXCEEDED, "图片不能超过 5MB"));
    }

    /** 路由不存在 / 静态资源不存在 / HTTP 方法不支持 → 404（JSON 而非白板页）。 */
    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<Result<Object>> handleNotFound(Exception ex) {
        return ResponseEntity.status(404).body(Result.fail(ErrorCode.COMMON_NOT_FOUND));
    }

    /**
     * 兜底：任何没被上面接住的异常 → 500。
     * 完整堆栈 + traceId 进日志（服务端可查），响应只给通用文案（不泄漏内部细节）。
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnknown(Exception ex) {
        log.error("Unhandled exception, traceId={}", com.wzblog.common.web.TraceId.current(), ex);
        return ResponseEntity.status(500).body(Result.fail(ErrorCode.COMMON_INTERNAL_ERROR));
    }
}
