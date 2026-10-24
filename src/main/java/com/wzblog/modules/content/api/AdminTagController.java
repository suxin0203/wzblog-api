package com.wzblog.modules.content.api;

import com.wzblog.common.api.Result;
import com.wzblog.modules.content.api.dto.TagUpsertRequest;
import com.wzblog.modules.content.api.vo.TagVO;
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
 * 后台标签 CRUD（物理删除，级联 article_tags）。
 */
@RestController
@RequestMapping("/admin/tags")
@PreAuthorize("hasAnyRole('EDITOR', 'ADMIN')")
public class AdminTagController {

    private final TaxonomyService taxonomyService;

    public AdminTagController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.ok(taxonomyService.listAdminTags());
    }

    @PostMapping
    public Result<TagVO> create(@Valid @RequestBody TagUpsertRequest request) {
        return Result.ok(taxonomyService.createTag(request));
    }

    @PutMapping("/{id}")
    public Result<TagVO> update(@PathVariable Long id,
                                @Valid @RequestBody TagUpsertRequest request) {
        return Result.ok(taxonomyService.updateTag(id, request));
    }

    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        taxonomyService.deleteTag(id);
        return Result.ok();
    }
}
