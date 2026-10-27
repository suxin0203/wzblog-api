package com.wzblog.modules.points.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.points.api.dto.OrderCreateRequest;
import com.wzblog.modules.points.api.dto.OrderTransitionRequest;
import com.wzblog.modules.points.api.vo.OrderVO;
import com.wzblog.modules.points.domain.Goods;
import com.wzblog.modules.points.domain.OrderLog;
import com.wzblog.modules.points.domain.OrderStatus;
import com.wzblog.modules.points.domain.PointsOrder;
import com.wzblog.modules.points.domain.PointsType;
import com.wzblog.modules.points.domain.event.OrderStatusChangedEvent;
import com.wzblog.modules.points.infra.mapper.GoodsMapper;
import com.wzblog.modules.points.infra.mapper.OrderLogMapper;
import com.wzblog.modules.points.infra.mapper.PointsOrderMapper;
import com.wzblog.modules.points.infra.mapper.TitleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.security.SecureRandom;

/**
 * 订单用例（M4 §8）：下单六步（幂等→库存→账户→流水→订单→日志）、
 * 取消退款回补、后台状态机矩阵。锁序恒为 商品 → 账户 → 订单。
 */
@Service
public class PointsOrderAppService {

    private static final Logger log = LoggerFactory.getLogger(PointsOrderAppService.class);
    private static final DateTimeFormatter ORDER_TIME = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final SecureRandom RANDOM = new SecureRandom();

    private final PointsOrderMapper orderMapper;
    private final OrderLogMapper orderLogMapper;
    private final GoodsMapper goodsMapper;
    private final TitleMapper titleMapper;
    private final PointsAppService pointsAppService;
    private final ApplicationEventPublisher eventPublisher;

    public PointsOrderAppService(PointsOrderMapper orderMapper, OrderLogMapper orderLogMapper,
                                 GoodsMapper goodsMapper, TitleMapper titleMapper,
                                 PointsAppService pointsAppService,
                                 ApplicationEventPublisher eventPublisher) {
        this.orderMapper = orderMapper;
        this.orderLogMapper = orderLogMapper;
        this.goodsMapper = goodsMapper;
        this.titleMapper = titleMapper;
        this.pointsAppService = pointsAppService;
        this.eventPublisher = eventPublisher;
    }

    // ------------------------------------------------------------ 下单

    /** 下单结果：fresh=false 表示幂等重放（HTTP 200）。 */
    public record CreatedOrder(OrderVO order, boolean fresh) {
    }

    @Transactional
    public CreatedOrder create(CurrentUser user, String idempotencyKey, OrderCreateRequest request) {
        validateIdempotencyKey(idempotencyKey);
        int quantity = request.quantity() == null ? 1 : request.quantity();
        if (quantity < 1 || quantity > 10) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "quantity 须为 1~10",
                    null);
        }

        // 1. 幂等预检：请求体任何字段不同都视为冲突（M4 §8.1），不静默返回旧订单
        PointsOrder existing = orderMapper.selectByIdempotency(user.id(), idempotencyKey);
        if (existing != null) {
            if (bodyChanged(existing, request, quantity)) {
                throw new BusinessException(ErrorCode.COMMON_IDEMPOTENCY_CONFLICT);
            }
            return new CreatedOrder(toVo(existing, false), false);
        }

        Goods goods = goodsMapper.selectByIdIncludeDeleted(request.goodsId());
        if (goods == null || goods.isDeleted() || !"ON_SALE".equals(goods.getStatus())) {
            throw new BusinessException(ErrorCode.GOODS_OFF_SHELF);
        }
        if ("TITLE".equals(goods.getType())) {
            if (request.receiverName() != null || request.receiverPhone() != null
                    || request.receiverAddress() != null) {
                throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                        "TITLE 商品无需收货信息", null);
            }
            if (goods.getTitleId() == null
                    || titleMapper.selectByIdIncludeDeleted(goods.getTitleId()) == null
                    || titleMapper.selectByIdIncludeDeleted(goods.getTitleId()).isDeleted()) {
                throw new BusinessException(ErrorCode.GOODS_TITLE_REQUIRED);
            }
            if (titleMapper.userOwnsTitle(user.id(), goods.getTitleId()) > 0) {
                throw new BusinessException(ErrorCode.ORDER_TITLE_ALREADY_OWNED);
            }
        } else {
            requireText(request.receiverName(), 50, "receiverName");
            requirePhone(request.receiverPhone());
            requireText(request.receiverAddress(), 255, "receiverAddress");
        }

        // 2. 条件扣库存（商品行锁，防超卖）
        if (goodsMapper.deductStock(goods.getId(), quantity, Times.nowUtc()) == 0) {
            throw new BusinessException(ErrorCode.GOODS_STOCK_INSUFFICIENT);
        }

        // 3~5. 锁账户扣分 + 写流水（锁序：商品已锁 → 账户）
        int total = goods.getPointsPrice() * quantity;
        String orderNo = generateOrderNo();
        try {
            pointsAppService.deduct(user.id(), total, PointsType.REDEEM, "ORDER", orderNo,
                    "redeem:" + orderNo, "兑换：" + goods.getName());
        } catch (BusinessException ex) {
            throw ex; // 余额不足 → 整体回滚（库存一并回滚）
        }

        // 6. 订单 + 日志
        PointsOrder order = new PointsOrder();
        order.setOrderNo(orderNo);
        order.setIdempotencyKey(idempotencyKey);
        order.setUserId(user.id());
        order.setGoodsId(goods.getId());
        order.setGoodsNameSnapshot(goods.getName());
        order.setGoodsTypeSnapshot(goods.getType());
        order.setUnitPoints(goods.getPointsPrice());
        order.setQuantity(quantity);
        order.setTotalPoints(total);
        order.setStatus(OrderStatus.PENDING.name());
        order.setReceiverName(request.receiverName());
        order.setReceiverPhone(request.receiverPhone());
        order.setReceiverAddress(request.receiverAddress());
        order.setUserRemark(request.userRemark());
        try {
            orderMapper.insert(order);
        } catch (DuplicateKeyException ex) {
            PointsOrder winner = orderMapper.selectByIdempotency(user.id(), idempotencyKey);
            if (winner != null) {
                return new CreatedOrder(toVo(winner, false), false); // 并发同键：唯一键兜底
            }
            throw ex;
        }
        orderLogMapper.insertLog(order.getId(), null, OrderStatus.PENDING.name(), user.id(),
                null, Times.nowUtc());
        return new CreatedOrder(toVo(order, false), true);
    }

    // ------------------------------------------------------------ 取消

    @Transactional
    public OrderVO cancel(CurrentUser user, String orderNo) {
        PointsOrder order = requireByOrderNo(orderNo);
        boolean admin = user.role() == Role.ADMIN;
        if (!admin && !Objects.equals(order.getUserId(), user.id())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND); // 越权不泄露存在
        }
        if (OrderStatus.CANCELLED.name().equals(order.getStatus())) {
            return toVo(order, false); // 幂等
        }
        OrderStatus from = OrderStatus.valueOf(order.getStatus());
        if (from != OrderStatus.PENDING && from != OrderStatus.APPROVED) {
            throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
        }
        refundAndRestore(order, OrderStatus.CANCELLED, "用户取消", null, user.id());
        return toVo(orderMapper.selectByOrderNo(orderNo), false);
    }

    // ------------------------------------------------------------ 后台流转

    @Transactional
    public OrderVO transition(CurrentUser admin, String orderNo, OrderTransitionRequest request) {
        PointsOrder order = requireByOrderNo(orderNo);
        OrderStatus from = OrderStatus.valueOf(order.getStatus());
        OrderStatus to = request.toStatus();
        switch (to) {
            case APPROVED -> {
                requireFrom(from, OrderStatus.PENDING);
                if (orderMapper.approve(order.getId(), request.adminRemark(), Times.nowUtc()) == 0) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
                }
                if ("TITLE".equals(order.getGoodsTypeSnapshot())) {
                    grantTitle(order);
                }
            }
            case REJECTED -> {
                requireFrom(from, OrderStatus.PENDING);
                if (request.adminRemark() == null || request.adminRemark().isBlank()) {
                    throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                            "驳回必须填写 adminRemark", null);
                }
                refundAndRestore(order, OrderStatus.REJECTED, request.adminRemark(),
                        request.adminRemark(), admin.id());
            }
            case CANCELLED -> {
                requireFrom(from, OrderStatus.PENDING, OrderStatus.APPROVED);
                refundAndRestore(order, OrderStatus.CANCELLED,
                        request.adminRemark() == null ? "管理员取消" : request.adminRemark(),
                        request.adminRemark(), admin.id());
            }
            case SHIPPED -> {
                requireFrom(from, OrderStatus.APPROVED);
                if ("PHYSICAL".equals(order.getGoodsTypeSnapshot())) {
                    if (request.logisticsCompany() == null || request.logisticsCompany().isBlank()
                            || request.logisticsCompany().codePoints().count() > 50
                            || request.logisticsNo() == null
                            || !request.logisticsNo().matches("^[A-Za-z0-9-]{1,64}$")) {
                        throw new BusinessException(ErrorCode.ORDER_LOGISTICS_REQUIRED);
                    }
                }
                if (orderMapper.ship(order.getId(), request.logisticsCompany(),
                        request.logisticsNo(), Times.nowUtc()) == 0) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
                }
            }
            case COMPLETED -> {
                requireFrom(from, OrderStatus.SHIPPED);
                if (orderMapper.complete(order.getId(), Times.nowUtc()) == 0) {
                    throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
                }
            }
            default -> throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
        }
        orderLogMapper.insertLog(order.getId(), from.name(), to.name(), admin.id(),
                request.adminRemark(), Times.nowUtc());
        PointsOrder refreshed = orderMapper.selectByOrderNo(orderNo);
        eventPublisher.publishEvent(new OrderStatusChangedEvent(refreshed.getId(), orderNo,
                refreshed.getUserId(), from.name(), to.name()));
        return toVo(refreshed, true);
    }

    // ------------------------------------------------------------ 读取

    @Transactional(readOnly = true)
    public OrderVO detail(CurrentUser user, String orderNo) {
        PointsOrder order = requireByOrderNo(orderNo);
        if (user.role() != Role.ADMIN && !Objects.equals(order.getUserId(), user.id())) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return toVo(order, true);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderVO> myList(CurrentUser user, OrderStatus status, int page,
                                      int pageSize) {
        long total = orderMapper.countUserPage(user.id(),
                status == null ? null : status.name());
        int offset = (page - 1) * pageSize;
        List<OrderVO> list = orderMapper
                .selectUserPage(user.id(), status == null ? null : status.name(), pageSize, offset)
                .stream().map(order -> toVo(order, false)).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    @Transactional(readOnly = true)
    public PageResult<OrderVO> adminList(Long userId, OrderStatus status, String orderNo,
                                         LocalDateTime from, LocalDateTime to, int page,
                                         int pageSize) {
        long total = orderMapper.countAdminPage(userId,
                status == null ? null : status.name(), orderNo, from, to);
        int offset = (page - 1) * pageSize;
        List<OrderVO> list = orderMapper
                .selectAdminPage(userId, status == null ? null : status.name(), orderNo, from, to,
                        pageSize, offset)
                .stream().map(order -> toVo(order, false)).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    // ------------------------------------------------------------ 内部

    /**
     * 退款 + 回补库存（锁序：商品 → 账户 → 订单条件更新）。
     * adminRemark 仅管理员操作写入；用户取消传 null，SQL 侧 COALESCE 保持原值不动。
     */
    private void refundAndRestore(PointsOrder order, OrderStatus to, String reason,
                                  String adminRemark, Long operatorId) {
        OrderStatus from = OrderStatus.valueOf(order.getStatus());
        // TITLE 订单已授予称号后取消：同步撤销称号，避免退款后白拿称号
        if (from == OrderStatus.APPROVED && "TITLE".equals(order.getGoodsTypeSnapshot())) {
            titleMapper.revokeByOrder(order.getId());
        }
        goodsMapper.restoreStock(order.getGoodsId(), order.getQuantity(), Times.nowUtc());
        pointsAppService.post(order.getUserId(), PointsType.REFUND, order.getTotalPoints(),
                "ORDER", order.getOrderNo(), "order-refund:" + order.getOrderNo(),
                "订单退款：" + order.getOrderNo(), null);
        int updated = orderMapper.cancelOrReject(order.getId(), from.name(), to.name(), reason,
                adminRemark, Times.nowUtc());
        if (updated == 0) {
            throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
        }
        orderLogMapper.insertLog(order.getId(), from.name(), to.name(), operatorId, reason,
                Times.nowUtc());
        eventPublisher.publishEvent(new OrderStatusChangedEvent(order.getId(), order.getOrderNo(),
                order.getUserId(), from.name(), to.name()));
    }

    private void grantTitle(PointsOrder order) {
        Goods goods = goodsMapper.selectByIdIncludeDeleted(order.getGoodsId());
        if (goods != null && goods.getTitleId() != null) {
            int granted = titleMapper.grantToUser(order.getUserId(), goods.getTitleId(),
                    order.getId());
            if (granted == 0) {
                // 已持有（如两笔同称号 PENDING 订单先后审批）：拒绝审批，让运营改走驳回退款
                throw new BusinessException(ErrorCode.ORDER_TITLE_ALREADY_OWNED);
            }
        }
    }

    private PointsOrder requireByOrderNo(String orderNo) {
        PointsOrder order = orderMapper.selectByOrderNo(orderNo);
        if (order == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return order;
    }

    private static void requireFrom(OrderStatus from, OrderStatus... allowed) {
        for (OrderStatus candidate : allowed) {
            if (from == candidate) {
                return;
            }
        }
        throw new BusinessException(ErrorCode.ORDER_STATE_INVALID);
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || !key.matches("^[\\x21-\\x7E]{8,64}$")) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "Idempotency-Key 缺失或非法（8~64 位可见 ASCII）", null);
        }
    }

    private static void requireText(String value, int max, String field) {
        if (value == null || value.isBlank() || value.codePoints().count() > max) {
            throw new BusinessException(ErrorCode.ORDER_RECEIVER_REQUIRED,
                    field + " 缺失或超长", null);
        }
    }

    private static void requirePhone(String value) {
        if (value == null || !value.matches("^1[3-9]\\d{9}$")) {
            throw new BusinessException(ErrorCode.ORDER_RECEIVER_REQUIRED, "receiverPhone 非法",
                    null);
        }
    }

    private String generateOrderNo() {
        String time = ORDER_TIME.format(LocalDateTime.now(ZoneOffset.UTC)
                .atZone(ZoneOffset.UTC).withZoneSameInstant(java.time.ZoneId.of("Asia/Shanghai")));
        for (int attempt = 0; attempt < 3; attempt++) {
            String suffix = randomSuffix(8);
            String orderNo = "PO" + time + suffix;
            if (orderMapper.selectByOrderNo(orderNo) == null) {
                return orderNo;
            }
        }
        return "PO" + time + randomSuffix(8);
    }

    private static String randomSuffix(int length) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        StringBuilder builder = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            builder.append(alphabet.charAt(RANDOM.nextInt(alphabet.length())));
        }
        return builder.toString();
    }

    /**
     * 幂等重放的请求体比对：订单已持久化全部下单字段，逐项对比即可判定
     * 「同 key 不同请求体」（M4 §8.1），无需额外的 hash 列。
     */
    private static boolean bodyChanged(PointsOrder existing, OrderCreateRequest request,
                                       int quantity) {
        return !Objects.equals(existing.getGoodsId(), request.goodsId())
                || existing.getQuantity() != quantity
                || !Objects.equals(existing.getReceiverName(), request.receiverName())
                || !Objects.equals(existing.getReceiverPhone(), request.receiverPhone())
                || !Objects.equals(existing.getReceiverAddress(), request.receiverAddress())
                || !Objects.equals(existing.getUserRemark(), request.userRemark());
    }

    private OrderVO toVo(PointsOrder order, boolean withLogs) {
        String maskedPhone = order.getReceiverPhone() == null ? null
                : order.getReceiverPhone().replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2");
        List<OrderVO.OrderLogVO> logs = withLogs
                ? orderLogMapper.selectByOrder(order.getId()).stream()
                        .map(this::toLogVo).toList()
                : List.of();
        return new OrderVO(order.getOrderNo(), String.valueOf(order.getUserId()),
                String.valueOf(order.getGoodsId()), order.getGoodsNameSnapshot(),
                order.getGoodsTypeSnapshot(), order.getUnitPoints(), order.getQuantity(),
                order.getTotalPoints(), order.getStatus(), order.getReceiverName(), maskedPhone,
                order.getReceiverAddress(), order.getLogisticsCompany(), order.getLogisticsNo(),
                order.getUserRemark(), order.getAdminRemark(), order.getCancelledReason(),
                Times.toInstant(order.getApprovedAt()), Times.toInstant(order.getShippedAt()),
                Times.toInstant(order.getCompletedAt()), Times.toInstant(order.getCancelledAt()),
                Times.toInstant(order.getCreatedAt()), Times.toInstant(order.getUpdatedAt()),
                logs, withLogs ? order.getReceiverPhone() : null);
    }

    private OrderVO.OrderLogVO toLogVo(OrderLog logEntry) {
        return new OrderVO.OrderLogVO(logEntry.getFromStatus(), logEntry.getToStatus(),
                logEntry.getOperatorId() == null ? null : String.valueOf(logEntry.getOperatorId()),
                logEntry.getRemark(), Times.toInstant(logEntry.getCreatedAt()));
    }
}
