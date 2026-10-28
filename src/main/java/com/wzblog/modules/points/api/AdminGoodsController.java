package com.wzblog.modules.points.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.points.api.dto.GoodsUpsertRequest;
import com.wzblog.modules.points.api.vo.GoodsVO;
import com.wzblog.modules.points.app.GoodsAppService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
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

/**
 * 商品后台路由（ADMIN）。
 */
@RestController
@RequestMapping("/admin/points/goods")
@PreAuthorize("hasRole('ADMIN')")
public class AdminGoodsController {

    private final GoodsAppService goodsAppService;

    public AdminGoodsController(GoodsAppService goodsAppService) {
        this.goodsAppService = goodsAppService;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<GoodsVO>>> list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "type", required = false) String type,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(goodsAppService.adminList(status, type, keyword,
                PageParams.requirePage(page), PageParams.requirePageSize(pageSize))));
    }

    @PostMapping
    public ResponseEntity<Result<GoodsVO>> create(@AuthenticationPrincipal CurrentUser admin,
                                                  @Valid @RequestBody GoodsUpsertRequest request) {
        return ResponseEntity.ok(Result.ok(goodsAppService.create(admin, request)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Result<GoodsVO>> update(@AuthenticationPrincipal CurrentUser admin,
                                                  @PathVariable("id") Long id,
                                                  @Valid @RequestBody GoodsUpsertRequest request) {
        return ResponseEntity.ok(Result.ok(goodsAppService.update(admin, id, request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Result<Object>> delete(@AuthenticationPrincipal CurrentUser admin,
                                                 @PathVariable("id") Long id) {
        goodsAppService.delete(admin, id);
        return ResponseEntity.ok(Result.ok(null));
    }
}
