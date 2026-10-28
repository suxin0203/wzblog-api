package com.wzblog.modules.points.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.Result;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.points.api.dto.OrderCreateRequest;
import com.wzblog.modules.points.api.vo.OrderVO;
import com.wzblog.modules.points.app.PointsOrderAppService;
import com.wzblog.modules.points.domain.OrderStatus;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 我的订单（USER）：下单必须携带 Idempotency-Key。
 */
@RestController
@RequestMapping("/points/orders")
public class PointsOrderController {

    private final PointsOrderAppService orderAppService;

    public PointsOrderController(PointsOrderAppService orderAppService) {
        this.orderAppService = orderAppService;
    }

    @GetMapping
    public ResponseEntity<Result<PageResult<OrderVO>>> list(
            @AuthenticationPrincipal CurrentUser user,
            @RequestParam(value = "status", required = false) OrderStatus status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        return ResponseEntity.ok(Result.ok(orderAppService.myList(user, status, PageParams.requirePage(page),
                PageParams.requirePageSize(pageSize))));
    }

    @GetMapping("/{orderNo}")
    public ResponseEntity<Result<OrderVO>> detail(@AuthenticationPrincipal CurrentUser user,
                                                  @PathVariable("orderNo") String orderNo) {
        return ResponseEntity.ok(Result.ok(orderAppService.detail(user, orderNo)));
    }

    @PostMapping
    public ResponseEntity<Result<OrderVO>> create(
            @AuthenticationPrincipal CurrentUser user,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody OrderCreateRequest request) {
        var result = orderAppService.create(user, idempotencyKey, request);
        return ResponseEntity.ok(Result.ok(result.order()));
    }

    @PostMapping("/{orderNo}/cancel")
    public ResponseEntity<Result<OrderVO>> cancel(@AuthenticationPrincipal CurrentUser user,
                                                  @PathVariable("orderNo") String orderNo) {
        return ResponseEntity.ok(Result.ok(orderAppService.cancel(user, orderNo)));
    }
}
