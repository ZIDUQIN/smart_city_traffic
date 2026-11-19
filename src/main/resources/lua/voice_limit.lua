-- 语音限流计数：一次调用完成"判超额 + 计数"，避免 CHECK 与 INCR 之间被并发钻空子
-- KEYS[1] 计数 key（单 key，集群下按它自己算槽，不会跨槽）
-- ARGV[1] mode：acquire / release
-- ARGV[2] limit：acquire 时的上限（release 忽略）
-- ARGV[3] ttl 秒：acquire 首次创建 key 时设置过期，保证 key 最终被回收
--
-- 返回：acquire 超额 -1，放行返回本次用量（>=1）；release 返回退还后剩余用量

if ARGV[1] == 'acquire' then
    local used = tonumber(redis.call('GET', KEYS[1]) or '0')
    if used >= tonumber(ARGV[2]) then
        return -1
    end
    local after = redis.call('INCR', KEYS[1])
    if after == 1 then
        redis.call('EXPIRE', KEYS[1], tonumber(ARGV[3]))
    end
    return after
end

local used = tonumber(redis.call('GET', KEYS[1]) or '0')
if used > 0 then
    redis.call('DECR', KEYS[1])
    return used - 1
end
return 0
