package com.wzblog.common.lock;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 带持有者标识的分布式锁：值用随机 token，释放前 Lua 比对——
 * 只有持有者本人能删锁，杜绝"超时后被他人抢走、原持有者 finally 误删他人锁"。
 *
 * <p>【教学：分布式锁最经典的两个坑】（详解见 docs/学习文档/13）</p>
 * <p><b>坑一：为什么锁值要用随机 token？</b>场景回放：</p>
 * <pre>
 * T0 实例A拿锁(ttl=30s) → 任务卡了40s → 锁自动过期
 * T1 实例B拿到锁开始跑
 * T2 实例A缓过来，finally 里无条件 DEL —— 把 B 的锁删了！实例C 又进来……
 * </pre>
 * <p>解法：每个锁实例自带随机 token（持有者身份证），释放前先比对"锁还是我的吗"。</p>
 * <p><b>坑二：为什么比对+删除必须 Lua？</b>"GET 比对"和"DEL"是两条命令，中间锁可能恰好易主
 * —— 又是 check-then-act 竞态。Lua 脚本在 Redis 单线程里原子执行比对+删除。</p>
 *
 * <p>【典型用法：定时任务多实例互斥】（四个后台任务的标准模板）</p>
 * <pre>{@code
 * @Scheduled(cron = "0 0 3 * * *")
 * void cleanup() {
 *     var lock = new OwnerAwareRedisLock(redis, "wzblog:lock:file-cleanup");
 *     if (!lock.tryAcquire(Duration.ofMinutes(10))) return;   // 别的实例在跑，撤退
 *     try { doWork(); } finally { lock.release(); }
 * }
 * }</pre>
 * <p>注意本类<b>刻意不是 Spring Bean</b>：token 是实例字段（持有者身份），每把锁都要
 * new 一个新实例 —— 注入单例复用 token 会破坏持有者语义。</p>
 *
 * <p>【能力边界】这是"尽力而为"的锁：不处理业务超时续期、不可重入。任务型互斥够用；
 * 需要锁等待/看门狗续期的场景请用 Redisson —— 但先理解手工版，再用轮子。</p>
 */
public final class OwnerAwareRedisLock {

    /** 释放脚本：GET 到的值等于我的 token 才 DEL（原子比对+删除，见类注释坑二）。 */
    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
                return redis.call('DEL', KEYS[1])
            end
            return 0
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String key;
    /** 持有者身份：每个锁实例一个随机 token，释放时凭它证明"锁还是我的"。 */
    private final String token = UUID.randomUUID().toString();

    public OwnerAwareRedisLock(StringRedisTemplate redis, String key) {
        this.redis = redis;
        this.key = key;
    }

    /**
     * 尝试获取锁（非阻塞）：SET key token NX EX ttl 一条命令完成"不存在才设置+过期"，
     * 返回 false 表示已被别人持有（或抢锁失败）。
     */
    public boolean tryAcquire(Duration ttl) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(key, token, ttl));
    }

    /** 释放：仅当锁仍属于本持有者（Lua 原子比对+删除）。 */
    public void release() {
        redis.execute(RELEASE_SCRIPT, List.of(key), token);
    }
}
