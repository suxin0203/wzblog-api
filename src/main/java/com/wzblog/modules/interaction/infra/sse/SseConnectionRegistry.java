package com.wzblog.modules.interaction.infra.sse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * SSE 连接注册表（单实例内存版，M3 §11.4）。
 * <p>每用户最多 3 条连接；达到上限时先关闭最旧连接再接纳新连接。
 * 20s 心跳用于探测已断开但未触发回调的连接，发送失败即清理。</p>
 */
@Component
public class SseConnectionRegistry {

    public static final int MAX_PER_USER = 3;
    /** 心跳间隔：20s，低于常见代理 60s 空闲断开阈值，兼顾开销与保活。 */
    public static final long SSE_HEARTBEAT_INTERVAL_MS = 20_000L;
    /** 超限拒绝时建议客户端的重试秒数。 */
    public static final long SSE_RETRY_AFTER_SECONDS = 5L;

    private static final Logger log = LoggerFactory.getLogger(SseConnectionRegistry.class);

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public boolean register(Long userId, SseEmitter emitter) {
        List<SseEmitter> list = byUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        synchronized (list) {
            if (list.size() >= MAX_PER_USER) {
                // 优先淘汰最旧连接，避免刷新几次后把用户锁在 429 里
                SseEmitter oldest = list.remove(0);
                evictQuietly(oldest);
                log.debug("SSE evict oldest userId={} remaining={}", userId, list.size());
            }
            if (list.size() >= MAX_PER_USER) {
                return false;
            }
            list.add(emitter);
        }
        emitter.onCompletion(() -> cleanup(userId, emitter, false));
        emitter.onTimeout(() -> cleanup(userId, emitter, true));
        emitter.onError(error -> cleanup(userId, emitter, false));
        log.debug("SSE registered userId={} count={}", userId, connectionCount(userId));
        return true;
    }

    /** 推送给某用户全部连接；返回是否至少送达一条。 */
    public boolean push(Long userId, SseEmitter.SseEventBuilder event) {
        List<SseEmitter> list = byUser.get(userId);
        if (list == null || list.isEmpty()) {
            return false;
        }
        boolean delivered = false;
        for (SseEmitter emitter : list) {
            try {
                emitter.send(event);
                delivered = true;
            } catch (IOException | IllegalStateException ex) {
                cleanup(userId, emitter, false);
            }
        }
        return delivered;
    }

    public int connectionCount(Long userId) {
        List<SseEmitter> list = byUser.get(userId);
        return list == null ? 0 : list.size();
    }

    /** 心跳扫描：发送失败即视为死连接，统一清理。 */
    @Scheduled(fixedDelay = SSE_HEARTBEAT_INTERVAL_MS)
    public void heartbeat() {
        byUser.forEach((userId, list) -> {
            for (SseEmitter emitter : list) {
                try {
                    emitter.send(SseEmitter.event().comment("ping"));
                } catch (IOException | IllegalStateException ex) {
                    // 连接已断开：只摘除，不再 complete（会触发 AsyncRequestNotUsableException）
                    cleanup(userId, emitter, false);
                    log.debug("SSE heartbeat drop userId={}", userId);
                }
            }
        });
    }

    /** onCompletion/onTimeout/onError/发送失败共用同一清理分支，保证计数一致。 */
    private void cleanup(Long userId, SseEmitter emitter, boolean complete) {
        List<SseEmitter> list = byUser.get(userId);
        if (list != null) {
            list.remove(emitter);
        }
        if (complete) {
            closeQuietly(emitter);
        }
        log.debug("SSE cleanup userId={} remaining={}", userId, connectionCount(userId));
    }

    private static void closeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (RuntimeException ignored) {
            // 已完成的连接重复 complete 属正常
        }
    }

    /** 淘汰前先探活：活连接发送 evicted 后正常关闭；死连接直接摘除，避免 complete 触发异步派发异常。 */
    private static void evictQuietly(SseEmitter emitter) {
        try {
            emitter.send(SseEmitter.event().comment("evicted"));
            emitter.complete();
        } catch (IOException | IllegalStateException ignored) {
            // 连接已不可用，仅摘除
        }
    }
}