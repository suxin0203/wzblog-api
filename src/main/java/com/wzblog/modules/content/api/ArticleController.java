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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 文章路由（M2 §3）：公开读取、阅读上报与编辑写路径。
 *
 * <p>【教学：一个标准 Controller 的自我修养】（详解见 monorepo 根 docs/学习文档/04 与 08）</p>
 * <ul>
 *   <li><b>路径不带 /api/v1</b>：统一前缀由 WebConfig 挂载，Controller 只写业务路径；</li>
 *   <li><b>构造器注入 + final 字段</b>：依赖显式、不可变、便于单测 mock
 *       （本项目统一风格，不用字段 @Autowired）；</li>
 *   <li><b>方法一行式</b>：参数绑定/校验/鉴权全靠注解，业务委托给 app 层服务，
 *     返回 {@code Result.ok(...)} 包装 —— Controller 里不写业务逻辑；</li>
 *   <li><b>读写服务分开注入</b>：ReadService（无事务、可缓存）与 WriteService
 *       （事务边界、发事件）职责分离，各自演化；</li>
 *   <li><b>权限分层</b>：公开读不加注解（SecurityConfig 路由级放行 GET），
 *       写操作方法级 @PreAuthorize 双保险；@AuthenticationPrincipal 拿当前用户
 *       （游客为 null），@Valid 触发 DTO 校验（失败由全局处理器转 400）。</li>
 * </ul>
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
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleCreatedVO> create(@AuthenticationPrincipal CurrentUser currentUser,
                                           @Valid @RequestBody ArticleCreateRequest request) {
        return Result.ok(writeService.create(currentUser, request));
    }

    @GetMapping("/{id}/edit")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> edit(@AuthenticationPrincipal CurrentUser currentUser,
                                      @PathVariable Long id) {
        return Result.ok(readService.edit(currentUser, id));
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
    public Result<Void> delete(@AuthenticationPrincipal CurrentUser currentUser,
                               @PathVariable Long id) {
        writeService.softDelete(currentUser, id);
        return Result.ok();
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> publish(@AuthenticationPrincipal CurrentUser currentUser,
                                         @PathVariable Long id) {
        return Result.ok(writeService.publish(currentUser, id));
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<ArticleEditVO> unpublish(@AuthenticationPrincipal CurrentUser currentUser,
                                           @PathVariable Long id) {
        return Result.ok(writeService.unpublish(currentUser, id));
    }

    @PostMapping("/{id}/pin")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> pin(@AuthenticationPrincipal CurrentUser currentUser,
                            @PathVariable Long id) {
        writeService.pin(currentUser, id);
        return Result.ok();
    }

    @DeleteMapping("/{id}/pin")
    @PreAuthorize("hasRole('ADMIN')")
    public Result<Void> unpin(@AuthenticationPrincipal CurrentUser currentUser,
                              @PathVariable Long id) {
        writeService.unpin(currentUser, id);
        return Result.ok();
    }

    @GetMapping("/{id}/revisions")
    @PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
    public Result<List<RevisionVO>> revisions(@AuthenticationPrincipal CurrentUser currentUser,
                                              @PathVariable Long id) {
        return Result.ok(readService.revisions(currentUser, id));
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
