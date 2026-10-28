package com.wzblog.common.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 带持有者标识的分布式锁：值用随机 token，释放前 Lua 比对——
 * 只有持有者本人能删锁，杜绝"超时后被他人抢走、原持有者 finally 误删他人锁"。
 */
public final class OwnerAwareRedisLock {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String key;
    private final String token = UUID.randomUUID().toString();

    public OwnerAwareRedisLock(StringRedisTemplate redis, String key) {
        this.redis = redis;
        this.key = key;
    }

    /** 尝试获取；owner 由实例随机 token 保证。 */
    public boolean tryAcquire(Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl));
    }

    /** 释放：仅当锁仍属于本持有者（Lua 原子比对+删除）。 */
    public void release() {
        redis.execute(RELEASE_SCRIPT, List.of(key), token);
    }
}
