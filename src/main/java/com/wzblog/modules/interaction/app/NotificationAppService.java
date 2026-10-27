package com.wzblog.modules.interaction.app;

import com.wzblog.common.api.PageResult;
import com.wzblog.common.error.BusinessException;
import com.wzblog.common.error.ErrorCode;
import com.wzblog.common.profile.UserBrief;
import com.wzblog.common.util.Times;
import com.wzblog.common.web.CurrentUser;
import com.wzblog.modules.identity.app.UserQueryService;
import com.wzblog.modules.interaction.api.vo.AuthorVO;
import com.wzblog.modules.interaction.api.vo.NotificationVO;
import com.wzblog.modules.interaction.api.vo.ReadResultVO;
import com.wzblog.modules.interaction.api.vo.UnreadCountVO;
import com.wzblog.modules.interaction.domain.Notification;
import com.wzblog.modules.interaction.infra.mapper.NotificationMapper;
import com.wzblog.modules.interaction.infra.sse.SseConnectionRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * 站内通知：dedupe_key 唯一键去重落库、Redis 未读数、SSE 推送与断线续传。
 */
@Service
public class NotificationAppService {

    private static final Logger log = LoggerFactory.getLogger(NotificationAppService.class);
    private static final Duration UNREAD_TTL = Duration.ofHours(1);
    private static final long SSE_TIMEOUT_MILLIS = 30 * 60 * 1000L;

    private final NotificationMapper notificationMapper;
    private final UserQueryService userQueryService;
    private final StringRedisTemplate redis;
    private final SseConnectionRegistry sseRegistry;

    public NotificationAppService(NotificationMapper notificationMapper,
                                  UserQueryService userQueryService,
                                  StringRedisTemplate redis,
                                  SseConnectionRegistry sseRegistry) {
        this.notificationMapper = notificationMapper;
        this.userQueryService = userQueryService;
        this.redis = redis;
        this.sseRegistry = sseRegistry;
    }

    // ------------------------------------------------------------ 写入（供本模块与事件监听）

    @Transactional
    public void create(Long recipientId, Long actorId, String type, String title, String content,
                       String linkType, String linkId, String dedupeKey) {
        if (recipientId == null) {
            return; // 无接收人（防御：游客内容的通知不应进入）
        }
        if (Objects.equals(recipientId, actorId)) {
            return; // 自己触发自己的动作不发通知
        }
        // Redis 快路径去重（最终一致性由 uk_dedupe 保证）
        if (dedupeKey != null) {
            String fastKey = "notify:dedupe:" + recipientId + ":" + dedupeKey;
            Boolean first = redis.opsForValue().setIfAbsent(fastKey, "1", Duration.ofDays(30));
            if (Boolean.FALSE.equals(first)) {
                return;
            }
        }
        Notification notification = new Notification();
        notification.setRecipientId(recipientId);
        notification.setActorId(actorId);
        notification.setType(type);
        notification.setTitle(truncate(title, 150));
        notification.setContent(truncate(content, 500));
        notification.setLinkType(linkType);
        notification.setLinkId(linkId);
        notification.setDedupeKey(dedupeKey);
        notification.setCreatedAt(Times.nowUtc());
        int inserted;
        try {
            inserted = notificationMapper.insertPlain(notification);
        } catch (org.springframework.dao.DuplicateKeyException ex) {
            inserted = 0;
        }
        if (inserted > 0) {
            incrementUnread(recipientId, 1);
            pushToUser(recipientId, notification);
        } else if (dedupeKey != null) {
            redis.delete("notify:dedupe:" + recipientId + ":" + dedupeKey); // 插入失败回滚快路径，允许重试
        }
    }

    // ------------------------------------------------------------ 读取

    @Transactional(readOnly = true)
    public PageResult<NotificationVO> list(CurrentUser user, int page, int pageSize) {
        long total = notificationMapper.countByRecipient(user.id());
        int offset = (page - 1) * pageSize;
        List<Notification> notifications = notificationMapper.selectPage(user.id(), pageSize, offset);
        Map<Long, UserBrief> actors = new HashMap<>();
        var ids = notifications.stream().map(Notification::getActorId).filter(Objects::nonNull)
                .distinct().toList();
        if (!ids.isEmpty()) {
            actors.putAll(userQueryService.getBriefs(ids));
        }
        List<NotificationVO> list = notifications.stream()
                .map(notification -> toVo(notification, actors))
                .toList();
        return new PageResult<>(list, total, page, pageSize, offset + list.size() < total);
    }

    public UnreadCountVO unreadCount(CurrentUser user) {
        String key = unreadKey(user.id());
        String cached = redis.opsForValue().get(key);
        if (cached != null && !cached.equals("-1")) {
            return new UnreadCountVO(Long.parseLong(cached));
        }
        long count = notificationMapper.countUnread(user.id());
        redis.opsForValue().set(key, String.valueOf(count), UNREAD_TTL);
        return new UnreadCountVO(count);
    }

    @Transactional
    public ReadResultVO markRead(CurrentUser user, List<String> ids) {
        Set<Long> parsed = new HashSet<>();
        for (String id : ids) {
            try {
                long value = Long.parseLong(id);
                if (value > 0) {
                    parsed.add(value);
                }
            } catch (NumberFormatException ignored) {
                // 非法 id 静默忽略
            }
        }
        if (parsed.isEmpty()) {
            return new ReadResultVO(0, unreadCount(user).count());
        }
        List<Long> ordered = List.copyOf(parsed);
        int updated = notificationMapper.markRead(user.id(), ordered, Times.nowUtc());
        if (updated > 0) {
            decrementUnread(user.id(), updated);
        }
        return new ReadResultVO(updated, unreadCount(user).count());
    }

    @Transactional
    public ReadResultVO markAllRead(CurrentUser user) {
        int updated = notificationMapper.markAllRead(user.id(), Times.nowUtc());
        redis.delete(unreadKey(user.id()));
        return new ReadResultVO(updated, 0);
    }

    // ------------------------------------------------------------ SSE

    public SseEmitter openStream(CurrentUser user, String lastEventId) {
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);
        if (!sseRegistry.register(user.id(), emitter)) {
            throw new BusinessException(ErrorCode.NOTIFY_STREAM_LIMITED);
        }
        try {
            emitter.send(SseEmitter.event().comment("ping"));
            if (lastEventId != null && !lastEventId.isBlank()) {
                long afterId = Long.parseLong(lastEventId.trim());
                for (Notification notification : notificationMapper.selectAfterId(user.id(),
                        afterId, 50)) {
                    emitter.send(SseEmitter.event()
                            .id(String.valueOf(notification.getId()))
                            .name("notification")
                            .data(toVo(notification, Map.of()), MediaType.APPLICATION_JSON));
                }
            }
            emitter.send(SseEmitter.event()
                    .name("ready")
                    .data(Map.of("unreadCount", unreadCount(user).count()),
                            MediaType.APPLICATION_JSON));
        } catch (IOException ex) {
            emitter.completeWithError(ex);
        }
        return emitter;
    }

    private void pushToUser(Long userId, Notification notification) {
        try {
            sseRegistry.push(userId, SseEmitter.event()
                    .id(String.valueOf(notification.getId()))
                    .name("notification")
                    .data(toVo(notification, Map.of()), MediaType.APPLICATION_JSON));
        } catch (Exception ex) {
            log.debug("SSE 推送失败 userId={}", userId, ex);
        }
    }

    // ------------------------------------------------------------ 未读数缓存

    private String unreadKey(Long userId) {
        return "notify:unread:" + userId;
    }

    private void incrementUnread(Long userId, int delta) {
        try {
            // key 存在才 INCR，避免缓存缺失时凭空计数
            redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "if redis.call('EXISTS', KEYS[1]) == 1 then return redis.call('INCRBY', KEYS[1], ARGV[1]) end return -1",
                    Long.class), List.of(unreadKey(userId)), String.valueOf(delta));
        } catch (Exception ex) {
            log.debug("未读数缓存失败 userId={}", userId);
        }
    }

    private void decrementUnread(Long userId, int delta) {
        try {
            redis.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(
                    "local v = redis.call('GET', KEYS[1]) if v then "
                            + "local n = tonumber(v) - tonumber(ARGV[1]) if n < 0 then n = 0 end "
                            + "return redis.call('SET', KEYS[1], n, 'KEEPTTL') end return -1",
                    Long.class), List.of(unreadKey(userId)), String.valueOf(delta));
        } catch (Exception ex) {
            log.debug("未读数缓存失败 userId={}", userId);
        }
    }

    // ------------------------------------------------------------ VO

    private NotificationVO toVo(Notification notification, Map<Long, UserBrief> actors) {
        UserBrief actor = notification.getActorId() == null ? null
                : actors.get(notification.getActorId());
        AuthorVO actorVO = actor == null ? null
                : new AuthorVO(actor.id(), actor.nickname(), actor.avatarKey(), false);
        return new NotificationVO(String.valueOf(notification.getId()), notification.getType(),
                notification.getTitle(), notification.getContent(), notification.getLinkType(),
                notification.getLinkId(), actorVO, Boolean.TRUE.equals(notification.getIsRead()),
                Times.toInstant(notification.getReadAt()), Times.toInstant(notification.getCreatedAt()));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.codePoints().count() <= max ? value
                : new String(value.codePoints().limit(max).toArray(), 0, max);
    }
}
