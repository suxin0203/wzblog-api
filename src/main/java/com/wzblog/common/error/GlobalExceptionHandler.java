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

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

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
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<Result<Object>> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex) {
        return ResponseEntity.status(403)
                .body(Result.fail(ErrorCode.AUTH_FORBIDDEN, ErrorCode.AUTH_FORBIDDEN.defaultMessage()));
    }

    @ExceptionHandler(org.springframework.security.core.AuthenticationException.class)
    public ResponseEntity<Result<Object>> handleAuthentication(
            org.springframework.security.core.AuthenticationException ex) {
        return ResponseEntity.status(401)
                .body(Result.fail(ErrorCode.AUTH_UNAUTHORIZED, ErrorCode.AUTH_UNAUTHORIZED.defaultMessage()));
    }

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
            String field = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
            errors.putIfAbsent(field, violation.getMessage());
        });
        return badRequestWithErrors(errors);
    }

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

    @ExceptionHandler({
            NoResourceFoundException.class,
            NoHandlerFoundException.class,
            HttpRequestMethodNotSupportedException.class
    })
    public ResponseEntity<Result<Object>> handleNotFound(Exception ex) {
        return ResponseEntity.status(404).body(Result.fail(ErrorCode.COMMON_NOT_FOUND));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Object>> handleUnknown(Exception ex) {
        log.error("Unhandled exception, traceId={}", com.wzblog.common.web.TraceId.current(), ex);
        return ResponseEntity.status(500).body(Result.fail(ErrorCode.COMMON_INTERNAL_ERROR));
    }
}