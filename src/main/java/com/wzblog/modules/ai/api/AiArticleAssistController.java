package com.wzblog.modules.ai.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.ai.api.dto.AiSummaryRequest;
import com.wzblog.modules.ai.api.dto.AiTagsRequest;
import com.wzblog.modules.ai.app.AiAssistService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 编辑辅助路由（EDITOR）：摘要/标签建议，不落库。
 */
@RestController
@RequestMapping("/articles/{id}/ai")
@PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
public class AiArticleAssistController {

    private final AiAssistService assistService;

    public AiArticleAssistController(AiAssistService assistService) {
        this.assistService = assistService;
    }

    @PostMapping("/summary")
    public ResponseEntity<Result<Map<String, Object>>> summary(
            @org.springframework.security.core.annotation.AuthenticationPrincipal CurrentUser user,
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AiSummaryRequest request) {
        AiSummaryRequest effective = request == null ? new AiSummaryRequest(null, null) : request;
        return ResponseEntity.ok(Result.ok(
                assistService.summarize(id, effective.style(), effective.length())));
    }

    @PostMapping("/tags")
    public ResponseEntity<Result<Map<String, Object>>> tags(
            @org.springframework.security.core.annotation.AuthenticationPrincipal CurrentUser user,
            @PathVariable("id") Long id,
            @Valid @RequestBody(required = false) AiTagsRequest request) {
        AiTagsRequest effective = request == null ? new AiTagsRequest(null) : request;
        return ResponseEntity.ok(Result.ok(assistService.suggestTags(id, effective.count())));
    }
}
