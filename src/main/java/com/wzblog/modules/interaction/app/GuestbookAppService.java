package com.wzblog.modules.interaction.app;

import com.wzblog.common.api.CursorPage;
import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.profile.UserBrief;
import com.wzblog.common.security.Role;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.ClientInfo;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.admin.app.AuditRecorder;
import com.wzblog.modules.admin.app.SiteConfigService;
import com.wzblog.modules.identity.app.UserQueryService;
import com.wzblog.modules.interaction.api.vo.AuthorVO;
import com.wzblog.modules.interaction.api.vo.GuestbookVO;
import com.wzblog.modules.interaction.domain.CommentStatus;
import com.wzblog.modules.interaction.domain.GuestbookMessage;
import com.wzblog.modules.interaction.infra.config.InteractionProperties;
import com.wzblog.modules.interaction.infra.mapper.GuestbookMapper;
import com.wzblog.modules.interaction.infra.redis.InteractionRateLimiter;
import com.wzblog.modules.interaction.infra.text.SensitiveWordFilter;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 留言板用例：与评论共用状态机/敏感词/限流，但无计数与跨模块事件。
 */
@Service
public class GuestbookAppService {

    private final GuestbookMapper guestbookMapper;
    private final UserQueryService userQueryService;
    private final SiteConfigService siteConfigService;
    private final SensitiveWordFilter sensitiveWordFilter;
    private final InteractionRateLimiter rateLimiter;
    private final InteractionProperties interactionProperties;
    private final AuditRecorder auditRecorder;

    public GuestbookAppService(GuestbookMapper guestbookMapper,
                               UserQueryService userQueryService,
                               SiteConfigService siteConfigService,
                               SensitiveWordFilter sensitiveWordFilter,
                               InteractionRateLimiter rateLimiter,
                               InteractionProperties interactionProperties,
                               AuditRecorder auditRecorder) {
        this.guestbookMapper = guestbookMapper;
        this.userQueryService = userQueryService;
        this.siteConfigService = siteConfigService;
        this.sensitiveWordFilter = sensitiveWordFilter;
        this.rateLimiter = rateLimiter;
        this.interactionProperties = interactionProperties;
        this.auditRecorder = auditRecorder;
    }

    @Transactional
    public GuestbookVO create(CurrentUser user, ClientInfo clientInfo, String content,
                              String nickname) {
        if (user == null && !siteConfigService.getBoolean("comment.guest_enabled", true)) {
            throw new BusinessException(ErrorCode.GUESTBOOK_DISABLED);
        }
        String normalized = content == null ? "" : content.trim();
        if (normalized.isEmpty()) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "content 不能为空", null);
        }
        if (normalized.codePoints().count() > 1000) {
            throw new BusinessException(ErrorCode.GUESTBOOK_CONTENT_TOO_LONG);
        }
        String name = user != null && user.role() != null ? null : normalizeNickname(nickname);
        if (user != null) {
            // 登录用户昵称取自资料，请求里的 nickname 忽略
            UserBrief brief = userQueryService.getBrief(user.id());
            name = brief == null ? "匿名" : brief.nickname();
        }
        rateLimiter.checkAndConsume("guestbook", "ip:" + clientInfo.ip(),
                interactionProperties.getGuestbookShortWindow(),
                interactionProperties.getGuestbookShortLimit(),
                interactionProperties.getGuestbookDailyLimit());

        sensitiveWordFilter.reload(siteConfigService.getStringList("comment.sensitive_words"));
        var maskResult = sensitiveWordFilter.mask(normalized);
        String masked = maskResult.masked();
        boolean sensitive = maskResult.hit();
        boolean audit = siteConfigService.getBoolean("comment.audit_enabled", true);

        GuestbookMessage message = new GuestbookMessage();
        message.setUserId(user == null ? null : user.id());
        message.setNickname(name == null ? "匿名" : name);
        message.setContent(masked);
        message.setStatus(sensitive || audit ? CommentStatus.PENDING : CommentStatus.APPROVED);
        message.setIp(clientInfo.ip());
        message.setUserAgent(truncate(clientInfo.userAgent(), 255));
        guestbookMapper.insert(message);
        return toVo(message, user);
    }

    @Transactional(readOnly = true)
    public CursorPage<GuestbookVO> publicList(CurrentUser user, String cursor, int size) {
        int offset = CommentAppService.CommentCursor.decode(cursor);
        int limit = Math.min(Math.max(size, 1), 50);
        List<GuestbookMessage> messages = guestbookMapper.selectPublicPage(limit + 1, offset);
        boolean hasNext = messages.size() > limit;
        if (hasNext) {
            messages = messages.subList(0, limit);
        }
        String nextCursor = hasNext ? CommentAppService.CommentCursor.encode(offset + limit) : null;
        List<GuestbookVO> list = messages.stream().map(m -> toVo(m, user)).toList();
        return CursorPage.of(list, nextCursor, hasNext);
    }

    @Transactional
    public void deleteByOwner(CurrentUser user, Long id) {
        GuestbookMessage message = requireExisting(id);
        if (user.role() != Role.ADMIN && !Objects.equals(message.getUserId(), user.id())) {
            throw new BusinessException(ErrorCode.AUTH_FORBIDDEN);
        }
        guestbookMapper.softDelete(id, Times.nowUtc());
    }

    @Transactional
    public CommentStatus moderate(CurrentUser editor, Long id, CommentStatus target, String remark) {
        if (target == CommentStatus.PENDING) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "目标状态不能为 PENDING",
                    null);
        }
        GuestbookMessage message = requireExisting(id);
        if (message.getStatus() == target) {
            return target;
        }
        boolean allowed = switch (message.getStatus()) {
            case PENDING -> target == CommentStatus.APPROVED || target == CommentStatus.REJECTED;
            case APPROVED -> target == CommentStatus.HIDDEN;
            case REJECTED, HIDDEN -> target == CommentStatus.APPROVED;
        };
        if (!allowed || guestbookMapper.transitionStatus(id, message.getStatus(), target,
                Times.nowUtc()) == 0) {
            throw new BusinessException(ErrorCode.GUESTBOOK_STATUS_INVALID);
        }
        auditRecorder.record(editor, "GUESTBOOK_STATUS", "GUESTBOOK", String.valueOf(id),
                "{\"status\":\"" + message.getStatus() + "\"}",
                "{\"status\":\"" + target + "\",\"remark\":\"" + safe(remark) + "\"}");
        return target;
    }

    @Transactional
    public void deleteByAdmin(CurrentUser editor, Long id) {
        requireExisting(id);
        if (guestbookMapper.softDelete(id, Times.nowUtc()) > 0) {
            auditRecorder.record(editor, "GUESTBOOK_STATUS", "GUESTBOOK", String.valueOf(id),
                    null, "{\"deleted\":true}");
        }
    }

    public PageResult<GuestbookVO> adminList(CommentStatus status, int page, int pageSize) {
        long total = guestbookMapper.countAdminPage(status);
        int offset = (page - 1) * pageSize;
        List<GuestbookMessage> messages = guestbookMapper.selectAdminPage(status, pageSize, offset);
        Map<Long, UserBrief> users = new HashMap<>();
        var ids = messages.stream().map(GuestbookMessage::getUserId).filter(Objects::nonNull).toList();
        if (!ids.isEmpty()) {
            users.putAll(userQueryService.getBriefs(ids));
        }
        List<GuestbookVO> list = messages.stream()
                .map(message -> toVo(message, null))
                .toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    // ------------------------------------------------------------ 内部

    private GuestbookMessage requireExisting(Long id) {
        GuestbookMessage message = guestbookMapper.selectByIdIncludeDeleted(id);
        if (message == null || message.isDeleted()) {
            throw new BusinessException(ErrorCode.GUESTBOOK_NOT_FOUND);
        }
        return message;
    }

    private GuestbookVO toVo(GuestbookMessage message, CurrentUser viewer) {
        boolean mine = viewer != null && Objects.equals(message.getUserId(), viewer.id());
        AuthorVO author = message.getUserId() == null
                ? AuthorVO.guest(message.getNickname())
                : new AuthorVO(message.getUserId(), message.getNickname(), null, false);
        return new GuestbookVO(String.valueOf(message.getId()), author, message.getContent(),
                Times.toInstant(message.getCreatedAt()), mine);
    }

    private String normalizeNickname(String nickname) {
        if (nickname == null || nickname.isBlank()) {
            return "匿名";
        }
        String trimmed = nickname.trim();
        long codePoints = trimmed.codePoints().count();
        if (codePoints < 1 || codePoints > 30) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR,
                    "nickname 长度须为 1~30 字符", null);
        }
        return trimmed;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static String safe(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
