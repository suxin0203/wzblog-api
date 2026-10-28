package com.wzblog.modules.points.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.Result;
import com.wzblog.modules.points.api.vo.GoodsVO;
import com.wzblog.modules.points.app.GoodsAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 商品公开读取（PUBLIC）。
 */
@RestController
@RequestMapping("/points/goods")
public class GoodsController {

    private final GoodsAppService goodsAppService;

    public GoodsController(GoodsAppService goodsAppService) {
        this.goodsAppService = goodsAppService;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<GoodsVO>>> list(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(goodsAppService.publicList(PageParams.requirePage(page),
                PageParams.requirePageSize(pageSize))));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Result<GoodsVO>> detail(@PathVariable("id") Long id) {
        return ResponseEntity.ok(Result.ok(goodsAppService.publicDetail(id)));
    }
}
