package com.wzblog.modules.interaction.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.interaction.api.dto.ModerateRequest;
import com.wzblog.modules.interaction.api.vo.CommentNodeVO;
import com.wzblog.modules.interaction.api.vo.GuestbookVO;
import com.wzblog.modules.interaction.api.vo.ModerationResultVO;
import com.wzblog.modules.interaction.app.CommentAppService;
import com.wzblog.modules.interaction.app.GuestbookAppService;
import com.wzblog.modules.interaction.domain.CommentStatus;
import com.wzblog.common.util.Times;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 评论/留言后台审核路由：评论 EDITOR（限自己文章）+ ADMIN；留言板仅 ADMIN。
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasAnyRole('EDITOR','ADMIN')")
public class InteractionAdminController {

    private final CommentAppService commentAppService;
    private final GuestbookAppService guestbookAppService;

    public InteractionAdminController(CommentAppService commentAppService,
                                      GuestbookAppService guestbookAppService) {
        this.commentAppService = commentAppService;
        this.guestbookAppService = guestbookAppService;
    }

    @GetMapping("/comments")
    public ResponseEntity<Result<PageResult<CommentNodeVO>>> comments(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "status", required = false) CommentStatus status,
            @RequestParam(value = "articleId", required = false) Long articleId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(commentAppService.adminList(user, status, articleId,
                Math.max(1, page), clamp(pageSize))));
    }

    @PutMapping("/comments/{id}/status")
    public ResponseEntity<Result<ModerationResultVO>> moderateComment(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable("id") Long id,
            @Valid @RequestBody ModerateRequest request) {
        CommentStatus target = parse(request.status());
        commentAppService.moderate(user, id, target, request.remark());
        return ResponseEntity.ok(Result.ok(new ModerationResultVO(String.valueOf(id),
                target.name(), java.time.Instant.now())));
    }

    @DeleteMapping("/comments/{id}")
    public ResponseEntity<Result<Object>> deleteComment(@AuthenticationPrincipal CurrentUser user,
                                                        @PathVariable("id") Long id) {
        commentAppService.deleteByAdmin(user, id);
        return ResponseEntity.ok(Result.ok(java.util.Map.of("id", String.valueOf(id))));
    }

    @GetMapping("/guestbook")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<PageResult<GuestbookVO>>> guestbook(
            @RequestParam(value = "status", required = false) CommentStatus status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(
                guestbookAppService.adminList(status, Math.max(1, page), clamp(pageSize))));
    }

    @PutMapping("/guestbook/{id}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<ModerationResultVO>> moderateGuestbook(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable("id") Long id,
            @Valid @RequestBody ModerateRequest request) {
        CommentStatus target = parse(request.status());
        guestbookAppService.moderate(user, id, target, request.remark());
        return ResponseEntity.ok(Result.ok(new ModerationResultVO(String.valueOf(id),
                target.name(), java.time.Instant.now())));
    }

    @DeleteMapping("/guestbook/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Object>> deleteGuestbook(@AuthenticationPrincipal CurrentUser user,
                                                          @PathVariable("id") Long id) {
        guestbookAppService.deleteByAdmin(user, id);
        return ResponseEntity.ok(Result.ok(java.util.Map.of("id", String.valueOf(id))));
    }

    private CommentStatus parse(String status) {
        try {
            return CommentStatus.valueOf(status.trim().toUpperCase());
        } catch (Exception ex) {
            throw new com.wzblog.common.error.BusinessException(
                    com.wzblog.common.error.ErrorCode.COMMON_VALIDATION_ERROR, "status 非法", null);
        }
    }

    private static int clamp(int pageSize) {
        return Math.min(Math.max(1, pageSize), 100);
    }
}
