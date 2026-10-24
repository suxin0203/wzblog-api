package com.wzblog.modules.content.api;

import com.wzblog.common.api.Result;
import com.wzblog.modules.content.api.vo.TagVO;
import com.wzblog.modules.content.app.TaxonomyService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 公开标签查询。
 */
@RestController
@RequestMapping("/tags")
public class TagController {

    private final TaxonomyService taxonomyService;

    public TagController(TaxonomyService taxonomyService) {
        this.taxonomyService = taxonomyService;
    }

    @GetMapping
    public Result<List<TagVO>> list() {
        return Result.ok(taxonomyService.listPublicTags());
    }

    @GetMapping("/{slug}")
    public Result<TagVO> detail(@PathVariable String slug) {
        return Result.ok(taxonomyService.getPublicTag(slug));
    }
}
