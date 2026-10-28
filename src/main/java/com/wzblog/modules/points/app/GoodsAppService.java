package com.wzblog.modules.points.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.media.FileBizType;
import com.wzblog.common.media.FileReferencePort;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.modules.points.api.dto.GoodsUpsertRequest;
import com.wzblog.modules.points.api.vo.GoodsVO;
import com.wzblog.modules.points.domain.Goods;
import com.wzblog.modules.points.infra.mapper.GoodsMapper;
import com.wzblog.modules.points.infra.mapper.TitleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 商品用例：公开读取 + 后台 CRUD（图片引用计数、审计）。
 */
@Service
public class GoodsAppService {

    private final GoodsMapper goodsMapper;
    private final TitleMapper titleMapper;
    private final FileReferencePort fileReferencePort;
    private final AuditRecorder auditRecorder;

    public GoodsAppService(GoodsMapper goodsMapper, TitleMapper titleMapper,
                           FileReferencePort fileReferencePort, AuditRecorder auditRecorder) {
        this.goodsMapper = goodsMapper;
        this.titleMapper = titleMapper;
        this.fileReferencePort = fileReferencePort;
        this.auditRecorder = auditRecorder;
    }

    @Transactional(readOnly = true)
    public PageResult<GoodsVO> publicList(int page, int pageSize) {
        long total = goodsMapper.countPublic();
        int offset = (page - 1) * pageSize;
        List<GoodsVO> list = goodsMapper.selectPublicPage(pageSize, offset)
                .stream().map(this::toVo).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    @Transactional(readOnly = true)
    public GoodsVO publicDetail(Long id) {
        Goods goods = goodsMapper.selectPublicById(id);
        if (goods == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return toVo(goods);
    }

    @Transactional
    public GoodsVO create(CurrentUser admin, GoodsUpsertRequest request) {
        validate(request, null);
        if (request.imageKey() != null && !request.imageKey().isBlank()) {
            fileReferencePort.claimByKey(request.imageKey().trim(), admin.id(), admin, FileBizType.GOODS);
        }
        Goods goods = new Goods();
        goods.setName(request.name().trim());
        goods.setType(request.type().name());
        goods.setTitleId(request.titleId());
        goods.setDescription(request.description());
        goods.setImageKey(normalize(request.imageKey()));
        goods.setPointsPrice(request.pointsPrice());
        goods.setStock(request.stock());
        goods.setSoldCount(0);
        goods.setStatus(request.status() == null ? "ON_SALE" : request.status().name());
        goods.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        goodsMapper.insert(goods);
        auditRecorder.record(admin, "GOODS_CREATE", "GOODS", String.valueOf(goods.getId()),
                null, "{\"name\":\"" + goods.getName() + "\"}");
        return toVo(goods);
    }

    @Transactional
    public GoodsVO update(CurrentUser admin, Long id, GoodsUpsertRequest request) {
        validate(request, id);
        Goods existing = goodsMapper.selectByIdIncludeDeleted(id);
        if (existing == null || existing.isDeleted()) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        if (request.status() == null) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "PUT 必须携带 status",
                    null);
        }
        String newImage = normalize(request.imageKey());
        String oldImage = existing.getImageKey();
        if (newImage != null && !newImage.equals(oldImage)) {
            fileReferencePort.claimByKey(newImage, admin.id(), admin, FileBizType.GOODS);
        }
        existing.setName(request.name().trim());
        existing.setType(request.type().name());
        existing.setTitleId(request.titleId());
        existing.setDescription(request.description());
        existing.setImageKey(newImage);
        existing.setPointsPrice(request.pointsPrice());
        existing.setStock(request.stock());
        existing.setStatus(request.status().name());
        existing.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
        goodsMapper.updateById(existing);
        if (oldImage != null && !oldImage.equals(newImage)) {
            fileReferencePort.releaseByKey(oldImage);
        }
        auditRecorder.record(admin, "GOODS_UPDATE", "GOODS", String.valueOf(id),
                "{\"stock\":" + (existing.getStock() == null ? -1 : existing.getStock()) + "}",
                "{\"stock\":" + (request.stock() == null ? -1 : request.stock()) + "}");
        return toVo(goodsMapper.selectByIdIncludeDeleted(id));
    }

    @Transactional
    public void delete(CurrentUser admin, Long id) {
        goodsMapper.softDelete(id, Times.nowUtc()); // 幂等：已删返回 0 行仍视为成功
        auditRecorder.record(admin, "GOODS_DELETE", "GOODS", String.valueOf(id), null, null);
    }

    @Transactional(readOnly = true)
    public PageResult<GoodsVO> adminList(String status, String type, String keyword, int page,
                                         int pageSize) {
        long total = goodsMapper.countAdminPage(status, type, keyword);
        int offset = (page - 1) * pageSize;
        List<GoodsVO> list = goodsMapper.selectAdminPage(status, type, keyword, pageSize, offset)
                .stream().map(goods -> toVo(goods)).toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    private void validate(GoodsUpsertRequest request, Long selfId) {
        if (request.type() == com.wzblog.modules.points.domain.GoodsType.TITLE) {
            if (request.titleId() == null || request.titleId() <= 0) {
                throw new BusinessException(ErrorCode.GOODS_TITLE_REQUIRED);
            }
            var title = titleMapper.selectByIdIncludeDeleted(request.titleId());
            if (title == null || title.isDeleted()) {
                throw new BusinessException(ErrorCode.TITLE_NOT_FOUND);
            }
        } else if (request.titleId() != null) {
            throw new BusinessException(ErrorCode.GOODS_TITLE_REQUIRED);
        }
        if (request.pointsPrice() == null || request.pointsPrice() < 1
                || request.pointsPrice() > 100_000) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "pointsPrice 须为 1~100000", null);
        }
        if (request.stock() != null && (request.stock() < 0 || request.stock() > 999_999)) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "stock 范围非法", null);
        }
    }

    private static String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private GoodsVO toVo(Goods goods) {
        String titleName = null;
        if (goods.getTitleId() != null) {
            var title = titleMapper.selectByIdIncludeDeleted(goods.getTitleId());
            titleName = title == null ? null : title.getName();
        }
        return new GoodsVO(String.valueOf(goods.getId()), goods.getName(), goods.getType(),
                goods.getTitleId() == null ? null : String.valueOf(goods.getTitleId()), titleName,
                goods.getDescription(), goods.getImageKey(), goods.getPointsPrice(),
                goods.getStock(), goods.getSoldCount(), goods.getStatus(), goods.getSortOrder(),
                Times.toInstant(goods.getCreatedAt()));
    }
}
