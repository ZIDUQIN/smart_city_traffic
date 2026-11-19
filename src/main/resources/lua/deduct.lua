-- 扣减脚本（区段模型）：判重 + 多区段检查 + 多区段扣减 + 已扣标记，原子完成
--
-- KEYS[1] = stock:{trainNo}:{seatNo}            Hash，field=区段号，value=余票
-- KEYS[2] = deducted:{trainNo}:{seatNo}         Set，成员 orderNo
-- KEYS[3] = passenger:dedup:{trainNo}:{passengerId}  String，乘车人占位
-- ARGV[1] = orderNo
-- ARGV[2..] = 区段号（独立逐个传入，禁止拼逗号字符串）
--
-- 返回：
--   >=0 成功，返回扣减后所有区段中的最小余票
--   -1  某区段库存不足
--   -2  stock Hash/field 不存在（未预热）
--   -3  该乘车人已购买该车次车票

-- ① 乘车人判重
if redis.call('EXISTS', KEYS[3]) == 1 then
    return -3
end

-- ② 先把所有区段检查一遍，一条数据都不改
for i = 2, #ARGV do
    local v = redis.call('HGET', KEYS[1], ARGV[i])
    if v == false or v == nil then
        return -2
    end
    if tonumber(v) <= 0 then
        return -1
    end
end

-- ③ 检查全过，逐个区段扣减，并记录最小余票
local minRemain = nil
for i = 2, #ARGV do
    local after = redis.call('HINCRBY', KEYS[1], ARGV[i], -1)
    if minRemain == nil or after < minRemain then
        minRemain = after
    end
end

-- ④ 已扣标记 + 乘车人占位
redis.call('SADD', KEYS[2], ARGV[1])
-- 乘车人占位带 3 天 TTL：实例过期清理时无法枚举每一条占位 key，
-- 靠过期自动消失，避免同一天重建后误拦正常购票
redis.call('SET', KEYS[3], ARGV[1], 'EX', 259200)

return minRemain
