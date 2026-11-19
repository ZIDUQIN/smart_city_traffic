-- 回补脚本（区段模型）：SREM 幂等判定 + 多区段回补 + 释放乘车人占位，原子完成
--
-- KEYS[1] = stock:{trainNo}:{seatNo}
-- KEYS[2] = deducted:{trainNo}:{seatNo}
-- KEYS[3] = passenger:dedup:{trainNo}:{passengerId}
-- ARGV[1] = orderNo
-- ARGV[2..] = 区段号（与扣减时一致，从流水 from/to 算出）
--
-- 返回：
--   1 确实移除过已扣标记，已把每个区段 +1 并释放乘车人占位
--   0 已补过或从未扣过（不动库存，但仍会释放属于本单的占位）

local removed = redis.call('SREM', KEYS[2], ARGV[1])

-- 占位释放与库存回补解耦：
-- deducted 集合可能因缓存清理/故障切换丢失（SREM 返回 0），
-- 但占位只要还属于本单就必须释放，否则该乘车人会被占位 TTL 锁住无法再买。
if redis.call('GET', KEYS[3]) == ARGV[1] then
    redis.call('DEL', KEYS[3])
end

if removed == 0 then
    return 0
end

-- 关键守卫：stock Hash 不存在时绝不能 HINCRBY。
-- 对不存在的 key 做 HINCRBY 会凭空造出只含当前区段的残缺 Hash，
-- 后续单区段查询信任它就会返回错误余票（幻影库存）。
-- 缓存缺失时跳过 Redis 回补，DB 才是最终事实来源，下次查询会整 Hash 回源重建。
if redis.call('EXISTS', KEYS[1]) == 0 then
    return 2
end

-- 每个区段 +1
for i = 2, #ARGV do
    redis.call('HINCRBY', KEYS[1], ARGV[i], 1)
end

return 1
