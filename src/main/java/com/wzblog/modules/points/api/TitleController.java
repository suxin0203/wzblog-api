package com.wzblog.modules.points.api;

import com.wzblog.common.api.Result;
import com.wzblog.modules.points.api.vo.TitleVO;
import com.wzblog.modules.points.app.TitleAppService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 称号公开列表（PUBLIC，不含用户关联）。
 */
@RestController
@RequestMapping("/titles")
public class TitleController {

    private final TitleAppService titleAppService;

    public TitleController(TitleAppService titleAppService) {
        this.titleAppService = titleAppService;
    }

    @GetMapping
    public ResponseEntity<Result<List<TitleVO>>> list() {
        return ResponseEntity.ok(Result.ok(titleAppService.activeList()));
    }
}
