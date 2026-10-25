package com.wzblog.modules.ai.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.ai.api.vo.AiAdminSessionVO;
import com.wzblog.modules.ai.api.vo.AiAdminStatsVO;
import com.wzblog.modules.ai.api.vo.AiFeedbackVO;
import com.wzblog.modules.ai.api.vo.AiMessageVO;
import com.wzblog.modules.ai.api.vo.AiQuotaVO;
import com.wzblog.modules.ai.api.vo.AiSessionVO;
import com.wzblog.modules.ai.domain.AiFeedback;
import com.wzblog.modules.ai.domain.AiMessage;
import com.wzblog.modules.ai.domain.AiSession;
import com.wzblog.modules.ai.infra.config.AiProperties;
import com.wzblog.modules.ai.infra.mapper.AiFeedbackMapper;
import com.wzblog.modules.ai.infra.mapper.AiMessageMapper;
import com.wzblog.modules.ai.infra.mapper.AiSessionMapper;
import com.wzblog.modules.ai.infra.redis.AiQuotaService;
import com.wzblog.modules.ai.infra.redis.GuestSessionStore;
import com.wzblog.modules.ai.infra.util.Ulid;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * AI 会话读取、反馈、配额视图与后台统计。
 */
@Service
public class AiSessionAppService {

    private final AiSessionMapper sessionMapper;
    private final AiMessageMapper messageMapper;
    private final AiFeedbackMapper feedbackMapper;
    private final GuestSessionStore guestSessionStore;
    private final AiQuotaService quotaService;
    private final AiProperties properties;

    public AiSessionAppService(AiSessionMapper sessionMapper, AiMessageMapper messageMapper,
                               AiFeedbackMapper feedbackMapper,
                               GuestSessionStore guestSessionStore, AiQuotaService quotaService,
                               AiProperties properties) {
        this.sessionMapper = sessionMapper;
        this.messageMapper = messageMapper;
        this.feedbackMapper = feedbackMapper;
        this.guestSessionStore = guestSessionStore;
        this.quotaService = quotaService;
        this.properties = properties;
    }

    /** 会话消息（游客默认不下发 TOOL 行）。 */
    @Transactional(readOnly = true)
    public List<AiMessageVO> messages(CurrentUser user, String guestToken, String sessionId,
                                      Long afterId, int size, boolean includeTool) {
        AiSession session = resolveSession(user, guestToken, sessionId);
        List<AiMessage> list = messageMapper.selectBySession(session.getId(), afterId,
                Math.min(Math.max(size, 1), 50));
        return list.stream()
                .filter(message -> includeTool || !AiMessage.ROLE_TOOL.equals(message.getRole()))
                .map(message -> new AiMessageVO(String.valueOf(message.getId()), message.getRole(),
                        message.getContent(), message.getStatus(), message.getToolName(),
                        Times.toInstant(message.getCreatedAt())))
                .toList();
    }

    @Transactional
    public void deleteSession(CurrentUser user, String guestToken, String sessionId) {
        AiSession session = resolveSession(user, guestToken, sessionId);
        softDeleteInternal(session);
    }

    /** 后台删除：越过归属校验（幂等）。 */
    @Transactional
    public void adminDelete(String sessionId) {
        AiSession session = sessionMapper.selectIncludeDeleted(sessionId);
        if (session == null || session.isDeleted()) {
            return;
        }
        softDeleteInternal(session);
    }

    private void softDeleteInternal(AiSession session) {
        sessionMapper.softDelete(session.getId(), Times.nowUtc());
        if (session.getUserId() == null) {
            guestSessionStore.revoke(session.getId());
        }
    }

    @Transactional
    public AiFeedbackVO feedback(CurrentUser user, String guestToken, Long messageId,
                                 int rating, String comment) {
        if (rating != 1 && rating != -1) {
            throw new BusinessException(ErrorCode.COMMON_VALIDATION_ERROR, "rating 仅允许 ±1", null);
        }
        AiMessage message = messageMapper.selectById(messageId);
        if (message == null || !AiMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            throw new BusinessException(ErrorCode.AI_FEEDBACK_INVALID);
        }
        // 会话归属校验（游客须持令牌）
        resolveSession(user, guestToken, message.getSessionId());
        Long feedbackUser = user == null ? null : user.id();
        if (feedbackMapper.countByMessageAndUser(messageId, feedbackUser) > 0) {
            throw new BusinessException(ErrorCode.AI_FEEDBACK_DUPLICATE);
        }
        AiFeedback feedback = new AiFeedback();
        feedback.setMessageId(messageId);
        feedback.setUserId(feedbackUser);
        feedback.setRating(rating);
        feedback.setComment(comment);
        feedback.setCreatedAt(Times.nowUtc());
        feedbackMapper.insertFeedback(feedback);
        return new AiFeedbackVO(String.valueOf(feedback.getId()), String.valueOf(messageId),
                rating, Times.toInstant(feedback.getCreatedAt()));
    }

    public AiQuotaVO quota(CurrentUser user, String ip) {
        if (!properties.isEnabled()) {
            return new AiQuotaVO(false, user == null ? "GUEST" : "USER",
                    new AiQuotaVO.Window(0, 0, 0, null),
                    new AiQuotaVO.Window(0, 0, 0, null));
        }
        var minute = quotaService.minuteUsage(ip);
        var daily = quotaService.dailyUsage(ip, user == null ? null : user.id());
        return new AiQuotaVO(true, user == null ? "GUEST" : "USER",
                new AiQuotaVO.Window(minute.limit(), minute.used(), minute.remaining(),
                        java.time.Instant.now().plusSeconds(60)),
                new AiQuotaVO.Window(daily.limit(), daily.used(), daily.remaining(),
                        java.time.Instant.now().plusSeconds(3600)));
    }

    // ------------------------------------------------------------ 后台

    public PageResult<AiAdminSessionVO> adminSessions(Long userId, String channel,
                                                      LocalDateTime from, LocalDateTime to,
                                                      int page, int pageSize) {
        long total = sessionMapper.countAdminPage(userId, channel, from, to);
        int offset = (page - 1) * pageSize;
        List<AiAdminSessionVO> list = sessionMapper
                .selectAdminPage(userId, channel, from, to, pageSize, offset)
                .stream().map(session -> new AiAdminSessionVO(session.getId(),
                        session.getUserId() == null ? null : String.valueOf(session.getUserId()),
                        session.getUserId() == null, session.getTitle(), session.getChannel(),
                        session.getMessageCount(), Times.toInstant(session.getLastMessageAt()),
                        Times.toInstant(session.getCreatedAt())))
                .toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    public AiSessionVO adminDetail(String sessionId) {
        AiSession session = sessionMapper.selectIncludeDeleted(sessionId);
        if (session == null) {
            throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        return toSessionVo(session);
    }

    public AiAdminStatsVO adminStats(LocalDateTime from, LocalDateTime to) {
        LocalDateTime safeFrom = from == null
                ? LocalDateTime.now(ZoneOffset.UTC).minusDays(7) : from;
        LocalDateTime safeTo = to == null ? LocalDateTime.now(ZoneOffset.UTC) : to;
        long messages = messageMapper.countInRange(safeFrom, safeTo);
        long promptTokens = messageMapper.sumPromptTokens(safeFrom, safeTo);
        long completionTokens = messageMapper.sumCompletionTokens(safeFrom, safeTo);
        long toolCalls = messageMapper.countToolCalls(safeFrom, safeTo);
        long avg = messageMapper.avgLatency(safeFrom, safeTo);
        long max = messageMapper.maxLatency(safeFrom, safeTo);
        long positive = feedbackMapper.countPositive(safeFrom, safeTo);
        long negative = feedbackMapper.countNegative(safeFrom, safeTo);
        Long sessions = sessionMapper.selectCount(null);
        Double rate = positive + negative == 0 ? null
                : (double) (positive - negative) / (positive + negative);
        return new AiAdminStatsVO(Times.toInstant(safeFrom), Times.toInstant(safeTo),
                sessions, messages, promptTokens, completionTokens, toolCalls, avg, max,
                positive, negative, rate);
    }

    /** M7 overview 用：今日消息数。 */
    public long todayMessageCount() {
        LocalDateTime start = java.time.LocalDate.now(ZoneOffset.UTC).atStartOfDay();
        return messageMapper.countInRange(start, LocalDateTime.now(ZoneOffset.UTC));
    }

    // ------------------------------------------------------------ 内部

    private AiSession resolveSession(CurrentUser user, String guestToken, String sessionId) {
        if (!Ulid.isValid(sessionId)) {
            throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        AiSession session = sessionMapper.selectIncludeDeleted(sessionId);
        if (session == null || session.isDeleted()) {
            throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
        }
        if (session.getUserId() != null) {
            if (user == null || !user.id().equals(session.getUserId())) {
                throw new BusinessException(ErrorCode.AI_SESSION_NOT_FOUND);
            }
            return session;
        }
        if (user != null) {
            throw new BusinessException(ErrorCode.AI_SESSION_FORBIDDEN);
        }
        if (!guestSessionStore.verify(sessionId, guestToken)) {
            throw new BusinessException(ErrorCode.AI_GUEST_TOKEN_INVALID);
        }
        return session;
    }

    private AiSessionVO toSessionVo(AiSession session) {
        return new AiSessionVO(session.getId(), session.getTitle(), session.getChannel(),
                session.getMessageCount(), Times.toInstant(session.getLastMessageAt()),
                Times.toInstant(session.getCreatedAt()),
                session.getUserId() == null ? Times.toInstant(session.getCreatedAt())
                        .plusSeconds(properties.getGuestSessionHours() * 3600L) : null);
    }
}
