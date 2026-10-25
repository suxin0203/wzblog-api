package com.wzblog.modules.interaction.api;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.api.Result;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.interaction.api.vo.MyReactionItemVO;
import com.wzblog.modules.interaction.api.vo.MyReactionVO;
import com.wzblog.modules.interaction.api.vo.ReactionVO;
import com.wzblog.modules.interaction.app.ReactionAppService;
import com.wzblog.modules.interaction.domain.ReactionType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 文章点赞/收藏与我的列表。
 */
@RestController
public class ReactionController {

    private final ReactionAppService reactionAppService;

    public ReactionController(ReactionAppService reactionAppService) {
        this.reactionAppService = reactionAppService;
    }

    @PutMapping("/articles/{id}/reactions/{type}")
    public ResponseEntity<Result<ReactionVO>> set(@AuthenticationPrincipal CurrentUser user,
                                                  @PathVariable("id") Long id,
                                                  @PathVariable("type") String type) {
        return ResponseEntity.ok(Result.ok(reactionAppService.setReaction(user, id, parse(type))));
    }

    @DeleteMapping("/articles/{id}/reactions/{type}")
    public ResponseEntity<Result<ReactionVO>> unset(@AuthenticationPrincipal CurrentUser user,
                                                    @PathVariable("id") Long id,
                                                    @PathVariable("type") String type) {
        return ResponseEntity.ok(Result.ok(reactionAppService.unsetReaction(user, id, parse(type))));
    }

    @GetMapping("/articles/{id}/reactions/me")
    public ResponseEntity<Result<MyReactionVO>> me(@AuthenticationPrincipal CurrentUser user,
                                                   @PathVariable("id") Long id) {
        return ResponseEntity.ok(Result.ok(reactionAppService.myStatus(user, id)));
    }

    @GetMapping("/me/favorites")
    public ResponseEntity<Result<CursorPage<MyReactionItemVO>>> favorites(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(Result.ok(
                reactionAppService.myList(user, ReactionType.FAVORITE, cursor, size)));
    }

    @GetMapping("/me/likes")
    public ResponseEntity<Result<CursorPage<MyReactionItemVO>>> likes(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "cursor", required = false) String cursor,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ResponseEntity.ok(Result.ok(
                reactionAppService.myList(user, ReactionType.LIKE, cursor, size)));
    }

    private ReactionType parse(String type) {
        if ("like".equalsIgnoreCase(type)) {
            return ReactionType.LIKE;
        }
        if ("favorite".equalsIgnoreCase(type)) {
            return ReactionType.FAVORITE;
        }
        throw new BusinessException(ErrorCode.REACTION_TYPE_INVALID);
    }
}
