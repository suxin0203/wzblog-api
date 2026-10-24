package com.wzblog.modules.content.api;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.api.dto.ArticleCreateRequest;
import com.wzblog.modules.content.api.dto.ArticleUpdateRequest;
import com.wzblog.modules.content.api.dto.RevisionRestoreRequest;
import com.wzblog.modules.content.api.dto.ViewRequest;
import com.wzblog.modules.content.api.vo.ArchiveItemVO;
import com.wzblog.modules.content.api.vo.ArticleCreatedVO;
import com.wzblog.modules.content.api.vo.ArticleDetailVO;
import com.wzblog.modules.content.api.vo.ArticleEditVO;
import com.wzblog.modules.content.api.vo.ArticleListItemVO;
import com.wzblog.modules.content.api.vo.RevisionVO;
import com.wzblog.modules.content.api.vo.SearchResultVO;
import com.wzblog.modules.content.api.vo.ViewResultVO;
import com.wzblog.modules.content.app.ArticleReadService;
import com.wzblog.modules.content.app.ArticleViewService;
import com.wzblog.modules.content.app.ArticleWriteService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文章路由（M2 §3）：公开读取、阅读上报与编辑写路径。
 */
@RestController
@RequestMapping("/articles")
public class ArticleController {

    private final ArticleReadService readService;
    private final ArticleWriteService writeService;
    private final ArticleViewService viewService;

    public ArticleController(ArticleReadService readService,
                             ArticleWriteService writeService,
                             ArticleViewService viewService) {
        this.readService = readService;
        this.writeService = writeService;
        this.viewService = viewService;
    }

    @GetMapping
    public Result<CursorPage<ArticleListItemVO>> feed(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String tagId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) Integer size) {
        return Result.ok(readService.feed(keyword, categoryId, tagId, year, month, cursor, size));
    }

    @GetMapping("/search")
    public Result<SearchResultVO> search(
            @RequestParam String keyword,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String tagId,
            @RequestParam(required = false) Integer size) {
        return Result.ok(readService.search(keyword, categoryId, tagId, size));
    }

    @GetMapping("/pinned")
    public Result<List<ArticleListItemVO>> pinned(@RequestParam(defaultValue = "10") int limit) {
        return Result.ok(readService.pinned(limit));
    }

    @GetMapping("/top")
    public Result<List<ArticleListItemVO>> top(@RequestParam(defaultValue = "view") String type,
                                               @RequestParam(defaultValue = "10") int limit) {
        return Result.ok(readService.top(type, limit));
    }

    @GetMapping("/archive")
    public Result<List<ArchiveItemVO>> archive(@RequestParam(required = false) Integer year) {
        return Result.ok(readService.archive(year));
    }

    @GetMapping("/{slug}")
    public Result<ArticleDetailVO> detail(@PathVariable String slug,
                                          @AuthenticationPrincipal CurrentUser currentUser) {
        return Result.ok(readService.detail(slug, currentUser));
    }

    @PostMapping("/{id}/view")
    public Result<ViewResultVO> view(@PathVariable Long id,
                                     @AuthenticationPrincipal CurrentUser currentUser,
                                     @RequestBody(required = false) ViewRequest request,
                                     HttpServletRequest httpRequest) {
        return Result.ok(viewService.record(id, currentUser,
                request == null ? null : request.source(),
                ClientInfo.from(httpRequest), httpRequest.getHeader("Referer")));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleCreatedVO> create(@AuthenticationPrincipal CurrentUser currentUser,
                                           @Valid @RequestBody ArticleCreateRequest request) {
        return Result.ok(writeService.create(currentUser, request));
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> edit(@PathVariable Long id) {
        return Result.ok(readService.edit(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> update(@AuthenticationPrincipal CurrentUser currentUser,
                                        @PathVariable Long id,
                                        @Valid @RequestBody ArticleUpdateRequest request) {
        return Result.ok(writeService.update(currentUser, id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<Void> delete(@PathVariable Long id) {
        writeService.softDelete(id);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> publish(@PathVariable Long id) {
        return Result.ok(writeService.publish(id));
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> unpublish(@PathVariable Long id) {
        return Result.ok(writeService.unpublish(id));
    }

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<Void> pin(@PathVariable Long id) {
        writeService.pin(id);
        return Result.ok();
    }

    @DeleteMapping("/{id}/pin")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<Void> unpin(@PathVariable Long id) {
        writeService.unpin(id);
        return Result.ok();
    }

    @GetMapping("/{id}/revisions")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<List<RevisionVO>> revisions(@PathVariable Long id) {
        return Result.ok(readService.revisions(id));
    }

    @PostMapping("/{id}/revisions/{revisionNo}/restore")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> restoreRevision(@AuthenticationPrincipal CurrentUser currentUser,
                                                 @PathVariable Long id,
                                                 @PathVariable Integer revisionNo,
                                                 @RequestBody(required = false) RevisionRestoreRequest request) {
        return Result.ok(writeService.restoreRevision(currentUser, id, revisionNo,
                request == null ? null : request.changeNote()));
    }
}
