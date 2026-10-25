package com.wzblog.modules.ai.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.ai.api.dto.AiChatRequest;
import com.wzblog.modules.ai.api.dto.AiFeedbackRequest;
import com.wzblog.modules.ai.api.vo.AiFeedbackVO;
import com.wzblog.modules.ai.api.vo.AiMessageVO;
import com.wzblog.modules.ai.api.vo.AiQuotaVO;
import com.wzblog.modules.ai.app.AiChatOrchestrator;
import com.wzblog.modules.ai.app.AiSessionAppService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

/**
 * AI 对话路由（OPTIONAL 鉴权：JWT 或游客令牌）。
 */
@RestController
@RequestMapping("/ai")
public class AiChatController {

    private final AiChatOrchestrator orchestrator;
    private final AiSessionAppService sessionAppService;

    public AiChatController(AiChatOrchestrator orchestrator,
                            AiSessionAppService sessionAppService) {
        this.orchestrator = orchestrator;
        this.sessionAppService = sessionAppService;
    }

    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@AuthenticationPrincipal CurrentUser user,
                           jakarta.servlet.http.HttpServletRequest httpRequest,
                           @RequestHeader(value = "X-AI-Session-Token", required = false)
                           String guestToken,
                           @Valid @RequestBody AiChatRequest request) {
        return orchestrator.chat(user, guestToken, ClientInfo.from(httpRequest),
                request.message(), request.sessionId());
    }

    @GetMapping("/sessions/{sessionId}/messages")
    public ResponseEntity<Result<List<AiMessageVO>>> messages(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "X-AI-Session-Token", required = false) String guestToken,
            @PathVariable("sessionId") String sessionId,
            @RequestParam(value = "cursor", required = false) Long afterId,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(Result.ok(sessionAppService.messages(user, guestToken, sessionId,
                afterId, size, false)));
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<Result<Object>> delete(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "X-AI-Session-Token", required = false) String guestToken,
            @PathVariable("sessionId") String sessionId) {
        sessionAppService.deleteSession(user, guestToken, sessionId);
        return ResponseEntity.ok(Result.ok(null));
    }

    @PostMapping("/messages/{messageId}/feedback")
    public ResponseEntity<Result<AiFeedbackVO>> feedback(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "X-AI-Session-Token", required = false) String guestToken,
            @PathVariable("messageId") Long messageId,
            @Valid @RequestBody AiFeedbackRequest request) {
        return ResponseEntity.ok(Result.ok(sessionAppService.feedback(user, guestToken, messageId,
                request.rating(), request.comment())));
    }

    @GetMapping("/quota")
    public ResponseEntity<Result<AiQuotaVO>> quota(@AuthenticationPrincipal CurrentUser user,
                                                   HttpServletRequest httpRequest) {
        return ResponseEntity.ok(Result.ok(sessionAppService.quota(user,
                ClientInfo.from(httpRequest).ip())));
    }
}
