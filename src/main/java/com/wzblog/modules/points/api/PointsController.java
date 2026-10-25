package com.wzblog.modules.points.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.points.api.vo.PointsAccountVO;
import com.wzblog.modules.points.api.vo.PointsLedgerVO;
import com.wzblog.modules.points.app.PointsQueryService;
import com.wzblog.modules.points.domain.PointsType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 积分账户与流水（USER）。
 */
@RestController
@RequestMapping("/points")
public class PointsController {

    private final PointsQueryService pointsQueryService;

    public PointsController(PointsQueryService pointsQueryService) {
        this.pointsQueryService = pointsQueryService;
    }

    @GetMapping("/account")
    public ResponseEntity<Result<PointsAccountVO>> account(
            @AuthenticationPrincipal CurrentUser user) {
        return ResponseEntity.ok(Result.ok(pointsQueryService.account(user)));
    }

    @GetMapping("/ledger")
    public ResponseEntity<Result<PageResult<PointsLedgerVO>>> ledger(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "type", required = false) PointsType type,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(pointsQueryService.myLedger(user, type,
                Math.max(1, page), Math.min(Math.max(1, pageSize), 100))));
    }
}
