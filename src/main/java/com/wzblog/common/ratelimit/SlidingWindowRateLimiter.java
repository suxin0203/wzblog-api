package com.wzblog.common.ratelimit;

import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * 滑动窗口限流（ZSET + Lua，一次网络往返原子完成）。
 * <p>相对固定窗口无边界 2x 突发问题；相对令牌桶无需补填速率，适合"每 N 秒/每天 M 次"类配额。
 * Spring Data Redis 按 SHA 缓存脚本，重复调用走 EVALSHA。</p>
 *
 * <p>【教学：算法原理】（详解见 docs/学习文档/13）</p>
 * <p>Redis ZSET 的 score 存"请求时间戳"。判定"最近 window 内有没有超过 limit 次"分三步：
 * 清窗口外旧记录（ZREMRANGEBYSCORE）→ 数一数（ZCARD）→ 没超就把自己记进去（ZADD）。
 * 三步必须是<b>原子</b>的 —— 若分开执行，两个并发请求会都查到"没超"然后都写入（超发）。
 * Redis 单线程执行 Lua 脚本天然原子，这就是"分布式的 check-then-act 必须用 Lua"的原因。</p>
 *
 * <p>【为什么不是注解 + AOP】本项目刻意用编程式调用（服务层显式
 * {@code rateLimiter.check(维度, 窗口, 额度)}）：哪个接口、按什么维度（IP/用户/场景）、
 * 什么窗口一目了然；也不掩盖每次判定都是一次 Redis 网络往返的成本。
 * 先学显式编程，再考虑抽象成注解。</p>
 *
 * <p>【典型用法】</p>
 * <pre>{@code
 * var v = rateLimiter.check("login:ip:" + ip, Duration.ofMinutes(1), 30);
 * if (!v.allowed()) throw new BusinessException(ErrorCode.COMMON_RATE_LIMITED, "...", v.retryAfterSeconds(), null);
 * }</pre>
 */
@Component
public class SlidingWindowRateLimiter {

    // 静态加载 Lua 脚本：classpath 资源 → RedisScript 对象，类加载时完成一次
    private static final RedisScript<Long> SCRIPT = buildScript();

    private final StringRedisTemplate redis;

    public SlidingWindowRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 判定结果值对象。
     * allowed=false 时 retryAfterSeconds ≥1（向上取整的毫秒→秒换算），
     * 调用方把它塞进 BusinessException 后，全局处理器会自动写 Retry-After 响应头。
     */
    public record Verdict(boolean allowed, long retryAfterSeconds) {
    }

    /**
     * 消耗一个名额并判定是否放行。
     * @param key    限流维度键（调用方自行组织，如 "comment:u:123" 或 "qr-create:ip:1.2.3.4"）
     * @param window 窗口长度（如 1 分钟、24 小时）
     * @param limit  窗口内最大允许次数
     * @return Verdict；脚本返回 0=放行，>0=建议等待的毫秒数
     */
    public Verdict check(String key, Duration window, int limit) {
        long now = System.currentTimeMillis();
        // member 必须全局唯一：同一毫秒的多次请求也各自占一个槽位（UUID 保证）
        String member = now + "-" + UUID.randomUUID();
        Long waitMs = redis.execute(SCRIPT, List.of(key),
                String.valueOf(now),
                String.valueOf(window.toMillis()),
                String.valueOf(limit),
                member);
        long wait = waitMs == null ? 0L : waitMs;
        // 毫秒向上取整为秒且至少 1 秒：给前端的 Retry-After 不能是 0
        return new Verdict(wait <= 0, wait <= 0 ? 0 : Math.max(1, (wait + 999) / 1000));
    }

    /** 只读查询当前窗口已用名额（清理过期后 ZCARD，非原子，仅供配额展示）。 */
    public long currentUsage(String key, Duration window) {
        long cutoff = System.currentTimeMillis() - window.toMillis();
        redis.opsForZSet().removeRangeByScore(key, 0, cutoff);
        Long size = redis.opsForZSet().zCard(key);
        return size == null ? 0 : size;
    }

    /** 组装 Lua 脚本对象（ResourceScriptSource 从 classpath 读取；Spring 按 SHA 缓存，重复调用走 EVALSHA）。 */
    private static RedisScript<Long> buildScript() {
        DefaultRedisScript<Long> script = new DefaultRedisScript<>();
        script.setScriptSource(new ResourceScriptSource(
                new ClassPathResource("lua/rate_limit_sliding_window.lua")));
        script.setResultType(Long.class);
        return script;
    }
}
