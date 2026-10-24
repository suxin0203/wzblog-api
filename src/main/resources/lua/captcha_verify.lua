-- 图形验证码：比对 + 成功删除 / 失败计数，第 N 次失败作废（原子）
-- KEYS[1] captcha hash key
-- ARGV[1] answer hash, ARGV[2] max fails
-- 返回：1=通过；0=不匹配；-1=不存在/过期；-2=达到失败上限并作废
local stored = redis.call('HGET', KEYS[1], 'hash')
if not stored then
    return -1
end
if stored == ARGV[1] then
    redis.call('DEL', KEYS[1])
    return 1
end
local fails = redis.call('HINCRBY', KEYS[1], 'fails', 1)
if fails >= tonumber(ARGV[2]) then
    redis.call('DEL', KEYS[1])
    return -2
end
return 0