package com.wzblog.modules.interaction.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.Result;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.interaction.api.dto.NotificationReadRequest;
import com.wzblog.modules.interaction.api.vo.NotificationVO;
import com.wzblog.modules.interaction.api.vo.ReadResultVO;
import com.wzblog.modules.interaction.api.vo.UnreadCountVO;
import com.wzblog.modules.interaction.app.NotificationAppService;
import jakarta.validation.Valid;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

/**
 * 站内通知路由：列表/未读数/已读/SSE。
 */
@RestController
@RequestMapping("/notifications")
public class NotificationController {

    private static final Logger log = LoggerFactory.getLogger(NotificationController.class);

    private final NotificationAppService notificationAppService;
    private final ObjectMapper objectMapper;

    public NotificationController(NotificationAppService notificationAppService, ObjectMapper objectMapper) {
        this.notificationAppService = notificationAppService;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<NotificationVO>>> list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(notificationAppService.list(user, PageParams.requirePage(page),
                PageParams.requirePageSize(pageSize))));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<Result<UnreadCountVO>> unreadCount(
            @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(Result.ok(notificationAppService.unreadCount(user)));
    }

    @PostMapping("/read")
    public ResponseEntity<Result<ReadResultVO>> read(@AuthenticationPrincipal CurrentUser user,
                                                     @Valid @RequestBody NotificationReadRequest request) {
        return ResponseEntity.ok(Result.ok(notificationAppService.markRead(user, request.ids())));
    }

    @PostMapping("/read-all")
    public ResponseEntity<Result<ReadResultVO>> readAll(@AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(Result.ok(notificationAppService.markAllRead(user)));
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@AuthenticationPrincipal CurrentUser user,
                             @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId,
                             HttpServletResponse response) throws IOException {
        try {
            return notificationAppService.openStream(user, lastEventId);
        } catch (BusinessException ex) {
            // Accept: text/event-stream 会让全局异常处理器的内容协商失败（500 空体），
            // 超限等同步业务异常必须直接按 JSON 包络写出，保住契约状态码与错误体。
            response.setStatus(ex.errorCode().httpStatus());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE + ";charset=UTF-8");
            if (ex.retryAfterSeconds() != null) {
                response.setHeader("Retry-After", String.valueOf(ex.retryAfterSeconds()));
            }
            response.getWriter().write(objectMapper.writeValueAsString(Result.fail(ex.errorCode())));
            return null;
        }
    }

    /** 仅 SSE 写流会因客户端断连抛 IOException；其他端点按普通 500 处理。 */
    @org.springframework.web.bind.annotation.ExceptionHandler(IOException.class)
    public void handleClientAbort(IOException ex, HttpServletRequest request,
                                  HttpServletResponse response) throws IOException {
        if (request.getRequestURI().endsWith("/notifications/stream")) {
            log.debug("SSE client aborted: {}", ex.getMessage());
            return;
        }
        log.error("Notification IO error", ex);
        if (!response.isCommitted()) {
            response.setStatus(500);
            response.setCharacterEncoding("UTF-8");
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(objectMapper.writeValueAsString(
                    Result.fail(ErrorCode.COMMON_INTERNAL_ERROR)));
        }
    }
}
