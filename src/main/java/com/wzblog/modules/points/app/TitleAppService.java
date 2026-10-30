package com.wzblog.modules.points.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.modules.points.api.dto.TitleUpsertRequest;
import com.wzblog.modules.points.api.vo.AdminTitleVO;
import com.wzblog.modules.points.api.vo.TitleVO;
import com.wzblog.modules.points.domain.Title;
import com.wzblog.modules.points.infra.mapper.TitleMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 称号用例：公开列表 + 后台 CRUD（名称唯一含软删占用）。
 */
@Service
public class TitleAppService {

    private final TitleMapper titleMapper;
    private final AuditRecorder auditRecorder;

    public TitleAppService(TitleMapper titleMapper, AuditRecorder auditRecorder) {
        this.titleMapper = titleMapper;
        this.auditRecorder = auditRecorder;
    }

    public List<TitleVO> activeList() {
        return titleMapper.selectActive().stream()
                .map(title -> new TitleVO(String.valueOf(title.getId()), title.getName(),
                        title.getCssClass(), title.getDescription(), title.getSortOrder()))
                .toList();
    }

    @Transactional
    public AdminTitleVO create(CurrentUser admin, TitleUpsertRequest request) {
        if (titleMapper.selectIdByName(request.name().trim(), null) != null) {
            throw new BusinessException(ErrorCode.TITLE_NAME_CONFLICT);
        }
        Title title = new Title();
        applyFields(title, request);
        title.setCreatedAt(Times.nowUtc());
        try {
            titleMapper.insert(title);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            // 软删称号名仍占用唯一索引，预检查看不到
            throw new BusinessException(ErrorCode.TITLE_NAME_CONFLICT);
        }
        auditRecorder.record(admin, "TITLE_CREATE", "TITLE", String.valueOf(title.getId()),
                null, "{\"name\":\"" + title.getName() + "\"}");
        return toAdminVo(title);
    }

    @Transactional
    public AdminTitleVO update(CurrentUser admin, Long id, TitleUpsertRequest request) {
        Title title = requireExisting(id);
        if (titleMapper.selectIdByName(request.name().trim(), id) != null) {
            throw new BusinessException(ErrorCode.TITLE_NAME_CONFLICT);
        }
        applyFields(title, request);
        title.setUpdatedAt(Times.nowUtc());
        try {
            titleMapper.updateById(title);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            throw new BusinessException(ErrorCode.TITLE_NAME_CONFLICT);
        }
        auditRecorder.record(admin, "TITLE_UPDATE", "TITLE", String.valueOf(id),
                null, "{\"name\":\"" + title.getName() + "\"}");
        return toAdminVo(title);
    }

    @Transactional
    public void delete(CurrentUser admin, Long id) {
        requireExisting(id);
        titleMapper.softDelete(id, Times.nowUtc());
        auditRecorder.record(admin, "TITLE_DELETE", "TITLE", String.valueOf(id), null, null);
    }

    public List<AdminTitleVO> adminList() {
        return titleMapper.selectAdminAll().stream()
                .map(title -> new AdminTitleVO(String.valueOf(title.getId()), title.getName(),
                        title.getCssClass(), title.getDescription(), title.getSortOrder(),
                        title.isDeleted(), Times.toInstant(title.getCreatedAt())))
                .toList();
    }

    private Title requireExisting(Long id) {
        Title title = titleMapper.selectByIdIncludeDeleted(id);
        if (title == null) {
            throw new BusinessException(ErrorCode.TITLE_NOT_FOUND);
        }
        return title;
    }

    private void applyFields(Title title, TitleUpsertRequest request) {
        title.setName(request.name().trim());
        title.setCssClass(request.cssClass());
        title.setDescription(request.description());
        title.setSortOrder(request.sortOrder() == null ? 0 : request.sortOrder());
    }

    private TitleVO toVo(Title title) {
        return new TitleVO(String.valueOf(title.getId()), title.getName(), title.getCssClass(),
                title.getDescription(), title.getSortOrder());
    }

    private AdminTitleVO toAdminVo(Title title) {
        return new AdminTitleVO(String.valueOf(title.getId()), title.getName(), title.getCssClass(),
                title.getDescription(), title.getSortOrder(), title.isDeleted(),
                Times.toInstant(title.getCreatedAt()));
    }
}
