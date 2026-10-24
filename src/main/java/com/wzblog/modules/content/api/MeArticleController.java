package com.wzblog.modules.content.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.content.api.vo.ArticleListItemVO;
import com.wzblog.modules.content.app.ArticleReadService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 作者文章列表（USER 可访问，通常为空；04 R0xx）。
 */
@RestController
@RequestMapping("/me/articles")
public class MeArticleController {

    private final ArticleReadService readService;

    public MeArticleController(ArticleReadService readService) {
        this.readService = readService;
    }

    @GetMapping
    public Result<PageResult<ArticleListItemVO>> list(
            @AuthenticationPrincipal CurrentUser currentUser,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        return Result.ok(readService.me(currentUser, status, page, pageSize));
    }
}
