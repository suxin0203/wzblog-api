package com.wzblog.modules.content.api;

import com.wzblog.common.api.Result;
import com.wzblog.modules.content.api.dto.CategoryUpsertRequest;
import com.wzblog.modules.content.api.vo.CategoryVO;
import com.wzblog.modules.content.app.TaxonomyService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 后台分类 CRUD（物理删除）。
 */
@RestController
@RequestMapping("/admin/categories")
@PreAuthorize("hasRole('ADMIN')")
public class AdminCategoryController {

    private final TaxonomyService taxonomyService;

    public AdminCategoryController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping
    public Result<List<CategoryVO>> list() {
        return Result.ok(taxonomyService.listAdminCategories());
    }

    @PostMapping
    public Result<CategoryVO> create(@Valid @RequestBody CategoryUpsertRequest request) {
        return Result.ok(taxonomyService.createCategory(request));
    }

    @PutMapping("/{id}")
    public Result<CategoryVO> update(@PathVariable Long id,
                                     @Valid @RequestBody CategoryUpsertRequest request) {
        return Result.ok(taxonomyService.updateCategory(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taxonomyService.deleteCategory(id);
        return Result.ok();
    }
}
