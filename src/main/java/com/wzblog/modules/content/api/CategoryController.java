package com.wzblog.modules.content.api;

import com.wzblog.common.api.Result;
import com.wzblog.modules.content.api.vo.CategoryVO;
import com.wzblog.modules.content.app.TaxonomyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开分类查询。
 */
@RestController
@RequestMapping("/categories")
public class CategoryController {

    private final TaxonomyService taxonomyService;

    public CategoryController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.ok(taxonomyService.listPublicCategories());
    }

    @GetMapping("/{slug}")
    public Result<CategoryVO> detail(@PathVariable String slug) {
        return Result.ok(taxonomyService.getPublicCategory(slug));
    }
}
