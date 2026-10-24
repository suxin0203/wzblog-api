package com.wzblog.modules.content.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.modules.content.api.vo.ArticleListItemVO;
import com.wzblog.modules.content.app.ArticleReadService;
import com.wzblog.modules.content.app.ArticleWriteService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 后台文章：列表（含回收站）、恢复、彻底删除。
 */
@RestController
@RequestMapping("/admin/articles")
@PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
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
    public Result<Void> restore(@PathVariable Long id) {
        writeService.restore(id);
        return Result.ok();
    }

    @DeleteMapping("/{id}/purge")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> purge(@PathVariable Long id) {
        writeService.purge(id);
        return Result.ok();
    }
}
