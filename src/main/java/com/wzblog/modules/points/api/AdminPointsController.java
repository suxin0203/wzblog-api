package com.wzblog.modules.points.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.points.api.dto.OrderTransitionRequest;
import com.wzblog.modules.points.api.dto.PointsAdjustRequest;
import com.wzblog.modules.points.api.vo.OrderVO;
import com.wzblog.modules.points.api.vo.PointsLedgerVO;
import com.wzblog.modules.points.app.PointsOrderAppService;
import com.wzblog.modules.points.app.PointsQueryService;
import com.wzblog.modules.points.domain.OrderStatus;
import com.wzblog.modules.points.domain.PointsType;
import jakarta.validation.Valid;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

/**
 * 订单/流水/调分后台路由（ADMIN）。
 */
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminPointsController {

    private final PointsOrderAppService orderAppService;
    private final PointsQueryService pointsQueryService;

    public AdminPointsController(PointsOrderAppService orderAppService,
                                 PointsQueryService pointsQueryService) {
        this.orderAppService = orderAppService;
        this.pointsQueryService = pointsQueryService;
    }

    @GetMapping("/points/orders")
    public ResponseEntity<Result<PageResult<OrderVO>>> orders(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "status", required = false) OrderStatus status,
            @RequestParam(value = "orderNo", required = false) String orderNo,
            @RequestParam(value = "createdFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "createdTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(orderAppService.adminList(userId, status, orderNo,
                from, to, PageParams.requirePage(page), PageParams.requirePageSize(pageSize))));
    }

    @GetMapping("/points/orders/{orderNo}")
    public ResponseEntity<Result<OrderVO>> orderDetail(@AuthenticationPrincipal CurrentUser admin,
                                                       @PathVariable("orderNo") String orderNo) {
        return ResponseEntity.ok(Result.ok(orderAppService.detail(admin, orderNo)));
    }

    @PostMapping("/points/orders/{orderNo}/transition")
    public ResponseEntity<Result<OrderVO>> transition(@AuthenticationPrincipal CurrentUser admin,
                                                      @PathVariable("orderNo") String orderNo,
                                                      @Valid @RequestBody OrderTransitionRequest request) {
        return ResponseEntity.ok(Result.ok(orderAppService.transition(admin, orderNo, request)));
    }

    @GetMapping("/points/ledger")
    public ResponseEntity<Result<PageResult<PointsLedgerVO>>> ledger(
            @RequestParam(value = "userId", required = false) Long userId,
            @RequestParam(value = "type", required = false) PointsType type,
            @RequestParam(value = "bizType", required = false) String bizType,
            @RequestParam(value = "createdFrom", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(value = "createdTo", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(pointsQueryService.adminLedger(userId, type, bizType,
                from, to, PageParams.requirePage(page), PageParams.requirePageSize(pageSize))));
    }

    @PostMapping("/users/{userId}/points")
    public ResponseEntity<Result<PointsLedgerVO>> adjust(
            @AuthenticationPrincipal CurrentUser admin,
            @PathVariable("userId") Long userId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody PointsAdjustRequest request) {
        return ResponseEntity.ok(Result.ok(
                pointsQueryService.adminAdjust(admin, userId, request.amount(), request.reason(),
                        idempotencyKey)));
    }
}
