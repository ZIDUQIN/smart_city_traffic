-- 令牌桶限流脚本（原子）：补令牌 + 判桶 + 扣令牌 一次完成
--
-- KEYS[1] = rate:{userId}:{trainNo}   Hash，field: tokens / last_ts
-- ARGV[1] = capacity          桶容量（最多允许的突发请求数）
-- ARGV[2] = refillPerSecond   每秒补充的令牌数（稳态放行速率）
-- ARGV[3] = nowTs             当前毫秒时间戳（由 Java 传入，统一时间源）
-- ARGV[4] = ttlMs             key 过期时间（毫秒），桶回满时间 + 冗余，防 key 永久堆积
--
-- 返回：
--   1  放行（已扣掉 1 个令牌）
--   0  拒绝（桶里不足 1 个令牌）
--
-- 为什么放在一个脚本里：读令牌数、补令牌、扣令牌必须原子，
-- 否则并发时会读到同一个令牌数导致超发，和库存"先查后扣"是同一个问题。

local key = KEYS[1]
local capacity = tonumber(ARGV[1])
local refillPerSecond = tonumber(ARGV[2])
local nowTs = tonumber(ARGV[3])
local ttlMs = tonumber(ARGV[4])

-- ① 读当前桶状态；两个字段都读不到视为首次请求，给满桶
local tokens = tonumber(redis.call('HGET', key, 'tokens'))
local lastTs = tonumber(redis.call('HGET', key, 'last_ts'))
if tokens == nil or lastTs == nil then
    tokens = capacity
    lastTs = nowTs
end

-- ② 按时间差补令牌，封顶为桶容量
local elapsed = nowTs - lastTs
if elapsed > 0 then
    local refill = elapsed / 1000.0 * refillPerSecond
    tokens = tokens + refill
    if tokens > capacity then
        tokens = capacity
    end
    lastTs = nowTs
end

-- ③ 桶里不足 1 个令牌：拒绝，不扣减；仍回写状态并设 TTL
if tokens < 1 then
    redis.call('HSET', key, 'tokens', string.format('%.4f', tokens), 'last_ts', tostring(lastTs))
    redis.call('PEXPIRE', key, ttlMs)
    return 0
end

-- ④ 放行：扣 1 个令牌后回写
tokens = tokens - 1
redis.call('HSET', key, 'tokens', string.format('%.4f', tokens), 'last_ts', tostring(lastTs))
redis.call('PEXPIRE', key, ttlMs)
return 1
