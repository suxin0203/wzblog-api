package com.wzblog.modules.interaction.api;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.interaction.api.dto.CreateCommentRequest;
import com.wzblog.modules.interaction.api.vo.CommentNodeVO;
import com.wzblog.modules.interaction.app.CommentAppService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章评论路由（M3 §3）。
 */
@RestController
@RequestMapping("/articles/{articleId}/comments")
public class ArticleCommentController {

    private final CommentAppService commentAppService;

    public ArticleCommentController(CommentAppService commentAppService) {
        this.commentAppService = commentAppService;
    }

    @GetMapping
    public ResponseEntity<Result<CursorPage<CommentNodeVO>>> list(
            @AuthenticationPrincipal CurrentUser user,
            @PathVariable("articleId") Long articleId,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "20") int size,
            @RequestParam(value = "rootId", required = false) String rootId) {
        return ResponseEntity.ok(Result.ok(
                commentAppService.list(user, articleId, cursor, size, rootId)));
    }

    @PostMapping
    public ResponseEntity<Result<CommentNodeVO>> create(
            @AuthenticationPrincipal CurrentUser user,
            jakarta.servlet.http.HttpServletRequest httpRequest,
            @PathVariable("articleId") Long articleId,
            @Valid @RequestBody CreateCommentRequest request) {
        CommentNodeVO created = commentAppService.create(user, ClientInfo.from(httpRequest),
                articleId, request.content(), request.parentId(), request.guestNickname());
        return ResponseEntity.status(201).body(Result.ok(created));
    }
}
