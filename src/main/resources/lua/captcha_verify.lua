-- =============================================================================
-- 图形验证码校验脚本：比对 + 成功删除 / 失败计数，第 N 次失败作废（原子）
-- （教学详解见 docs/学习文档/13；Java 侧见 identity 的 CaptchaStore）
--
-- 【为什么必须用 Lua】"比对答案 + 删除/计数 + 达限作废"如果拆成多条命令：
--   - 同一个验证码可被并发重复比对（爆破窗口）；
--   - 通过与作废可能交错（通过了又被删）。脚本化后四件事一个原子完成。
--
-- 【设计要点】
--   - 验证码答案只存哈希（SHA-256），Redis 被拖库也偷不走答案；
--   - 通过即 DEL：一次性使用，防止同一码反复利用；
--   - 失败计数（HINCRBY fails）：错满 max-fails 次直接 DEL 作废 —— 爆破者必须
--     每错 3 次就重新获取新码，配合获取接口的限流把爆破成本抬到不可行。
--
-- 存储结构：Redis Hash，字段 hash=答案哈希、fails=已失败次数
-- 入参：KEYS[1] captcha hash key；ARGV[1] answer hash, ARGV[2] max fails
-- 返回码即状态：1=通过；0=不匹配；-1=不存在/过期；-2=达到失败上限并作废
--   （用返回码而不是布尔表达多状态，是脚本接口设计的常用手法）
-- =============================================================================
local stored = redis.call('HGET', KEYS[1], 'hash')
if not stored then
    return -1                     -- 验证码不存在或已过期/已用掉
end
if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])    -- 通过即删：一次性使用
    return 1
end
local fails = redis.call('HINCRBY', KEYS[1], 'fails', 1)
if fails >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])    -- 错满上限：作废，必须重新获取新码
    return -2
end
return 0                          -- 还有剩余尝试机会
