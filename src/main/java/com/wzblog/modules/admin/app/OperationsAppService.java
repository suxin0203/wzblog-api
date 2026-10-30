package com.wzblog.modules.admin.app;

import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.media.FileBizType;
import com.wzblog.common.media.FileReferencePort;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.domain.Banner;
import com.wzblog.modules.admin.domain.FriendLink;
import com.wzblog.modules.admin.infra.mapper.BannerMapper;
import com.wzblog.modules.admin.infra.mapper.FriendLinkMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 轮播与友链用例：公开读取 + 后台 CRUD（轮播图走 M5 引用计数）。
 */
@Service
public class OperationsAppService {

    private final BannerMapper bannerMapper;
    private final FriendLinkMapper friendLinkMapper;
    private final FileReferencePort fileReferencePort;
    private final AuditRecorder auditRecorder;

    public OperationsAppService(BannerMapper bannerMapper, FriendLinkMapper friendLinkMapper,
                                FileReferencePort fileReferencePort, AuditRecorder auditRecorder) {
        this.bannerMapper = bannerMapper;
        this.friendLinkMapper = friendLinkMapper;
        this.fileReferencePort = fileReferencePort;
        this.auditRecorder = auditRecorder;
    }

    // ------------------------------------------------------------ 轮播

    public List<Banner> activeBanners() {
        return bannerMapper.selectActive();
    }

    public List<Banner> adminBanners(int page, int pageSize) {
        return bannerMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<Banner>()
                        .orderByAsc(Banner::getSortOrder).orderByAsc(Banner::getId)
                        .last("LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize));
    }

    public long countBanners() {
        return bannerMapper.selectCount(null);
    }

    @Transactional
    public Banner createBanner(CurrentUser admin, Banner banner) {
        requireBannerFields(banner);
        if (banner.getImageKey() != null && !banner.getImageKey().isBlank()) {
            fileReferencePort.claimByKey(banner.getImageKey().trim(), admin.id(), admin,
                    FileBizType.BANNER);
        }
        banner.setId(null);
        banner.setCreatedAt(Times.nowUtc());
        bannerMapper.insert(banner);
        auditRecorder.record(admin, "CONFIG_UPDATE", "SYSTEM", "banner:" + banner.getId(),
                null, "{\"title\":\"" + safe(banner.getTitle()) + "\"}");
        return banner;
    }

    @Transactional
    public Banner updateBanner(CurrentUser admin, Long id, Banner request) {
        requireBannerFields(request);
        Banner existing = requireBanner(id);
        String oldImage = existing.getImageKey();
        if (request.getImageKey() != null && !request.getImageKey().equals(oldImage)) {
            fileReferencePort.claimByKey(request.getImageKey(), admin.id(), admin, FileBizType.BANNER);
        }
        existing.setTitle(request.getTitle());
        existing.setImageKey(request.getImageKey());
        existing.setLinkUrl(request.getLinkUrl());
        existing.setSortOrder(request.getSortOrder());
        existing.setStatus(request.getStatus());
        existing.setStartAt(request.getStartAt());
        existing.setEndAt(request.getEndAt());
        // LambdaUpdateWrapper 显式 set：updateById 默认跳过 null 列，会导致"清空图"落不了库
        bannerMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<Banner>()
                        .eq(Banner::getId, existing.getId())
                        .set(Banner::getTitle, existing.getTitle())
                        .set(Banner::getImageKey, existing.getImageKey())
                        .set(Banner::getLinkUrl, existing.getLinkUrl())
                        .set(Banner::getSortOrder, existing.getSortOrder())
                        .set(Banner::getStatus, existing.getStatus())
                        .set(Banner::getStartAt, existing.getStartAt())
                        .set(Banner::getEndAt, existing.getEndAt())
                        .set(Banner::getUpdatedAt, Times.nowUtc()));
        if (oldImage != null && !oldImage.equals(request.getImageKey())) {
            fileReferencePort.releaseByKey(oldImage);
        }
        auditRecorder.record(admin, "CONFIG_UPDATE", "SYSTEM", "banner:" + id,
                "{\"title\":\"" + safe(existing.getTitle()) + "\"}",
                "{\"title\":\"" + safe(request.getTitle()) + "\"}");
        return existing;
    }

    @Transactional
    public void deleteBanner(CurrentUser admin, Long id) {
        Banner existing = requireBanner(id);
        bannerMapper.deleteById(id);
        if (existing.getImageKey() != null) {
            fileReferencePort.releaseByKey(existing.getImageKey());
        }
        auditRecorder.record(admin, "CONFIG_UPDATE", "SYSTEM", "banner:" + id,
                "{\"title\":\"" + safe(existing.getTitle()) + "\"}", "{\"deleted\":true}");
    }

    private Banner requireBanner(Long id) {
        Banner banner = bannerMapper.selectById(id);
        if (banner == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return banner;
    }

    /** 基础入参校验：image_key 为 NOT NULL，缺失/超长会直接 500，这里提前转 400。 */
    private static void requireBannerFields(Banner banner) {
        if (banner.getImageKey() == null || banner.getImageKey().isBlank()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "轮播图片不能为空", null);
        }
        if (banner.getTitle() != null && banner.getTitle().length() > 100) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "轮播标题不能超过 100 字", null);
        }
        if (banner.getLinkUrl() != null && banner.getLinkUrl().length() > 500) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "跳转地址不能超过 500 字", null);
        }
        if (banner.getStatus() != null && !"ENABLED".equals(banner.getStatus())
                && !"DISABLED".equals(banner.getStatus())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "轮播状态取值非法", null);
        }
    }

    // ------------------------------------------------------------ 友链

    public List<FriendLink> activeFriendLinks() {
        return friendLinkMapper.selectActive();
    }

    public List<FriendLink> adminFriendLinks(int page, int pageSize) {
        return friendLinkMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FriendLink>()
                        .orderByAsc(FriendLink::getSortOrder).orderByAsc(FriendLink::getId)
                        .last("LIMIT " + pageSize + " OFFSET " + (page - 1) * pageSize));
    }

    public long countFriendLinks() {
        return friendLinkMapper.selectCount(null);
    }

    @Transactional
    public FriendLink createFriendLink(CurrentUser admin, FriendLink link) {
        requireFriendLinkFields(link);
        link.setId(null);
        link.setCreatedAt(Times.nowUtc());
        friendLinkMapper.insert(link);
        auditRecorder.record(admin, "CONFIG_UPDATE", "SYSTEM", "friend-link:" + link.getId(),
                null, "{\"blogName\":\"" + safe(link.getBlogName()) + "\"}");
        return link;
    }

    @Transactional
    public FriendLink updateFriendLink(CurrentUser admin, Long id, FriendLink request) {
        requireFriendLinkFields(request);
        FriendLink existing = requireFriendLink(id);
        existing.setBlogName(request.getBlogName());
        existing.setBlogUrl(request.getBlogUrl());
        existing.setTheme(request.getTheme());
        existing.setBloggerName(request.getBloggerName());
        existing.setContact(request.getContact());
        existing.setLogoKey(request.getLogoKey());
        existing.setSortOrder(request.getSortOrder());
        existing.setStatus(request.getStatus());
        friendLinkMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<FriendLink>()
                        .eq(FriendLink::getId, existing.getId())
                        .set(FriendLink::getBlogName, existing.getBlogName())
                        .set(FriendLink::getBlogUrl, existing.getBlogUrl())
                        .set(FriendLink::getTheme, existing.getTheme())
                        .set(FriendLink::getBloggerName, existing.getBloggerName())
                        .set(FriendLink::getContact, existing.getContact())
                        .set(FriendLink::getLogoKey, existing.getLogoKey())
                        .set(FriendLink::getSortOrder, existing.getSortOrder())
                        .set(FriendLink::getStatus, existing.getStatus())
                        .set(FriendLink::getUpdatedAt, Times.nowUtc()));
        auditRecorder.record(admin, "CONFIG_UPDATE", "SYSTEM", "friend-link:" + id,
                "{\"blogName\":\"" + safe(existing.getBlogName()) + "\"}",
                "{\"blogName\":\"" + safe(request.getBlogName()) + "\"}");
        return existing;
    }

    @Transactional
    public void deleteFriendLink(CurrentUser admin, Long id) {
        FriendLink existing = requireFriendLink(id);
        friendLinkMapper.deleteById(id);
        auditRecorder.record(admin, "CONFIG_UPDATE", "SYSTEM", "friend-link:" + id,
                "{\"blogName\":\"" + safe(existing.getBlogName()) + "\"}", "{\"deleted\":true}");
    }

    private FriendLink requireFriendLink(Long id) {
        FriendLink link = friendLinkMapper.selectById(id);
        if (link == null) {
            throw new BusinessException(ErrorCode.COMMON_NOT_FOUND);
        }
        return link;
    }

    /** 基础入参校验：两列均为 NOT NULL，缺失/超长会直接 500，这里提前转 400。 */
    private static void requireFriendLinkFields(FriendLink link) {
        if (link.getBlogName() == null || link.getBlogName().isBlank()
                || link.getBlogName().length() > 100) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "站点名不能为空且不超过 100 字", null);
        }
        if (link.getBlogUrl() == null || !link.getBlogUrl().startsWith("http")
                || link.getBlogUrl().length() > 500) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "站点地址非法", null);
        }
        if (link.getStatus() != null && !"ENABLED".equals(link.getStatus())
                && !"DISABLED".equals(link.getStatus())) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "友链状态取值非法", null);
        }
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
