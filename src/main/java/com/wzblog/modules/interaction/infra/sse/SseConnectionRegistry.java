package com.wzblog.modules.interaction.infra.sse;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;

/**
 * SSE 连接注册表（单实例内存版，M3 §11.4）。
 * 每用户最多 3 条连接；推送失败即摘除。
 */
@Component
public class SseConnectionRegistry {

    public static final int MAX_PER_USER = 3;

    private final Map<Long, CopyOnWriteArrayList<SseEmitter>> byUser = new ConcurrentHashMap<>();

    public boolean register(Long userId, SseEmitter emitter) {
        List<SseEmitter> list = byUser.computeIfAbsent(userId, key -> new CopyOnWriteArrayList<>());
        // 锁内 check-then-add：CopyOnWriteArrayList 的 add 本身原子但 size 检查不是
        synchronized (list) {
            if (list.size() >= MAX_PER_USER) {
                return false;
            }
            list.add(emitter);
        }
        emitter.onCompletion(() -> list.remove(emitter));
        emitter.onTimeout(() -> {
            list.remove(emitter);
            emitter.complete();
        });
        emitter.onError(error -> list.remove(emitter));
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
                list.remove(emitter);
            }
        }
        return delivered;
    }

    public int connectionCount(Long userId) {
        List<SseEmitter> list = byUser.get(userId);
        return list == null ? 0 : list.size();
    }
}
