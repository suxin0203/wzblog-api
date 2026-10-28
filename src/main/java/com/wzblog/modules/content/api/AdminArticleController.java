package com.wzblog.modules.content.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.api.dto.ArticleTakeoverRequest;
import com.wzblog.modules.content.api.vo.ArticleEditVO;
import com.wzblog.modules.content.api.vo.ArticleListItemVO;
import com.wzblog.modules.content.app.ArticleReadService;
import com.wzblog.modules.content.app.ArticleWriteService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台文章（站务治理）：列表（含回收站）、恢复、彻底删除、接管。
 * A 方案：治理动作全部 ADMIN；作者用 /me/articles 与 /articles/{id}/*。
 */
@RestController
@RequestMapping("/admin/articles")
@PreAuthorize("hasRole('ADMIN')")
public class AdminArticleController {

    private final ArticleReadService readService;
    private final ArticleWriteService writeService;

    public AdminArticleController(ArticleReadService readService, ArticleWriteService writeService) {
        this.readService = readService;
        this.writeService = writeService;
    }

    @GetMapping
    public Result<PageResult<ArticleListItemVO>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String visibility,
            @RequestParam(required = false) Boolean deleted,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String authorId,
            @RequestParam(required = false) String sort,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(readService.admin(status, visibility, deleted, keyword,
                categoryId, authorId, sort, page, pageSize));
    }

    @PostMapping("/{id}/restore")
    public Result<Void> restore(@AuthenticationPrincipal CurrentUser currentUser,
                                @PathVariable Long id) {
        writeService.restore(currentUser, id);
        return Result.ok();
    }

    @DeleteMapping("/{id}/purge")
    public Result<Void> purge(@AuthenticationPrincipal CurrentUser currentUser,
                              @PathVariable Long id) {
        writeService.purge(currentUser, id);
        return Result.ok();
    }

    /** 接管：ADMIN 成为作者后才能编辑他人文章；审计 + 通知原作者。 */
    @PostMapping("/{id}/takeover")
    public Result<ArticleEditVO> takeover(@AuthenticationPrincipal CurrentUser currentUser,
                                          @PathVariable Long id,
                                          @Valid @RequestBody(required = false) ArticleTakeoverRequest request) {
        return Result.ok(writeService.takeover(currentUser, id,
                request == null ? null : request.reason()));
    }
}
