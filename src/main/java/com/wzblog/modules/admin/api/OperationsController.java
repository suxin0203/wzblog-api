package com.wzblog.modules.admin.api;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.api.PageParams;
import com.wzblog.common.api.Result;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.app.OperationsAppService;
import com.wzblog.modules.admin.domain.Banner;
import com.wzblog.modules.admin.domain.FriendLink;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 轮播与友链路由（公开读取 + ADMIN 管理）。
 */
@RestController
public class OperationsController {

    private final OperationsAppService operationsAppService;

    public OperationsController(OperationsAppService operationsAppService) {
        this.operationsAppService = operationsAppService;
    }

    // ------------------------------------------------------------ 公开

    @GetMapping("/banners")
    public ResponseEntity<Result<List<Map<String, Object>>>> banners() {
        List<Map<String, Object>> list = operationsAppService.activeBanners().stream()
                .map(banner -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", String.valueOf(banner.getId()));
                    item.put("title", banner.getTitle());
                    item.put("imageKey", banner.getImageKey());
                    item.put("linkUrl", banner.getLinkUrl());
                    item.put("sortOrder", banner.getSortOrder());
                    return item;
                }).toList();
        return ResponseEntity.ok(Result.ok(list));
    }

    @GetMapping("/friend-links")
    public ResponseEntity<Result<List<Map<String, Object>>>> friendLinks() {
        List<Map<String, Object>> list = operationsAppService.activeFriendLinks().stream()
                .map(link -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("id", String.valueOf(link.getId()));
                    item.put("blogName", link.getBlogName());
                    item.put("blogUrl", link.getBlogUrl());
                    item.put("theme", link.getTheme());
                    item.put("bloggerName", link.getBloggerName());
                    item.put("logoKey", link.getLogoKey());
                    return item;
                }).toList();
        return ResponseEntity.ok(Result.ok(list));
    }

    // ------------------------------------------------------------ 轮播后台

    @GetMapping("/admin/banners")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<PageResult<Map<String, Object>>>> adminBanners(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        int safePage = PageParams.requirePage(page);
        int safePageSize = PageParams.requirePageSize(pageSize);
        List<Banner> banners = operationsAppService.adminBanners(safePage, safePageSize);
        long total = operationsAppService.countBanners();
        return ResponseEntity.ok(Result.ok(new PageResult<>(
                banners.stream().map(this::bannerVo).toList(), total, safePage, safePageSize,
                (long) safePage * safePageSize < total)));
    }

    @PostMapping("/admin/banners")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Map<String, Object>>> createBanner(
            @AuthenticationPrincipal CurrentUser admin, @RequestBody Banner banner) {
        return ResponseEntity.ok(Result.ok(
                bannerVo(operationsAppService.createBanner(admin, banner))));
    }

    @PutMapping("/admin/banners/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Map<String, Object>>> updateBanner(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable("id") Long id,
            @RequestBody Banner banner) {
        return ResponseEntity.ok(Result.ok(
                bannerVo(operationsAppService.updateBanner(admin, id, banner))));
    }

    @DeleteMapping("/admin/banners/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Object>> deleteBanner(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable("id") Long id) {
        operationsAppService.deleteBanner(admin, id);
        return ResponseEntity.ok(Result.ok(null));
    }

    // ------------------------------------------------------------ 友链后台

    @GetMapping("/admin/friend-links")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<PageResult<Map<String, Object>>>> adminFriendLinks(
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "pageSize", defaultValue = "20") int pageSize) {
        int safePage = PageParams.requirePage(page);
        int safePageSize = PageParams.requirePageSize(pageSize);
        List<FriendLink> links = operationsAppService.adminFriendLinks(safePage, safePageSize);
        long total = operationsAppService.countFriendLinks();
        return ResponseEntity.ok(Result.ok(new PageResult<>(
                links.stream().map(this::linkVo).toList(), total, safePage, safePageSize,
                (long) safePage * safePageSize < total)));
    }

    @PostMapping("/admin/friend-links")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Map<String, Object>>> createFriendLink(
            @AuthenticationPrincipal CurrentUser admin, @RequestBody FriendLink link) {
        return ResponseEntity.ok(Result.ok(
                linkVo(operationsAppService.createFriendLink(admin, link))));
    }

    @PutMapping("/admin/friend-links/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Map<String, Object>>> updateFriendLink(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable("id") Long id,
            @RequestBody FriendLink link) {
        return ResponseEntity.ok(Result.ok(
                linkVo(operationsAppService.updateFriendLink(admin, id, link))));
    }

    @DeleteMapping("/admin/friend-links/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Result<Object>> deleteFriendLink(
            @AuthenticationPrincipal CurrentUser admin, @PathVariable("id") Long id) {
        operationsAppService.deleteFriendLink(admin, id);
        return ResponseEntity.ok(Result.ok(null));
    }

    private Map<String, Object> bannerVo(Banner banner) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(banner.getId()));
        item.put("title", banner.getTitle());
        item.put("imageKey", banner.getImageKey());
        item.put("linkUrl", banner.getLinkUrl());
        item.put("sortOrder", banner.getSortOrder());
        item.put("status", banner.getStatus());
        item.put("startAt", Times.toInstant(banner.getStartAt()));
        item.put("endAt", Times.toInstant(banner.getEndAt()));
        return item;
    }

    private Map<String, Object> linkVo(FriendLink link) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("id", String.valueOf(link.getId()));
        item.put("blogName", link.getBlogName());
        item.put("blogUrl", link.getBlogUrl());
        item.put("theme", link.getTheme());
        item.put("bloggerName", link.getBloggerName());
        item.put("contact", link.getContact());
        item.put("logoKey", link.getLogoKey());
        item.put("sortOrder", link.getSortOrder());
        item.put("status", link.getStatus());
        return item;
    }
}
