package com.wzblog.modules.interaction.api;

import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.interaction.api.vo.CommentLikeVO;
import com.wzblog.modules.interaction.app.CommentAppService;
import com.wzblog.modules.interaction.app.ReactionAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 单条评论路由：删除与点赞。
 */
@RestController
@RequestMapping("/comments")
public class CommentController {

    private final CommentAppService commentAppService;
    private final ReactionAppService reactionAppService;

    public CommentController(CommentAppService commentAppService,
                             ReactionAppService reactionAppService) {
        this.commentAppService = commentAppService;
        this.reactionAppService = reactionAppService;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Object>> delete(@AuthenticationPrincipal CurrentUser user,
                                                 @PathVariable("id") Long id) {
        commentAppService.deleteByOwner(user, id);
        return ResponseEntity.ok(Result.ok(java.util.Map.of("id", String.valueOf(id))));
    }

    @PostMapping("/{id}/like")
    public ResponseEntity<Result<CommentLikeVO>> like(@AuthenticationPrincipal CurrentUser user,
                                                      @PathVariable("id") Long id) {
        return ResponseEntity.ok(Result.ok(reactionAppService.likeComment(user, id)));
    }

    @DeleteMapping("/{id}/like")
    public ResponseEntity<Result<CommentLikeVO>> unlike(@AuthenticationPrincipal CurrentUser user,
                                                        @PathVariable("id") Long id) {
        return ResponseEntity.ok(Result.ok(reactionAppService.unlikeComment(user, id)));
    }
}
