-- 预检脚本（只读，不写任何数据）：乘车人判重 + 多区段库存检查
--
-- KEYS[1] = stock:{trainNo}:{seatNo}                  Hash，field=区段号，value=余票
-- KEYS[2] = passenger:dedup:{trainNo}:{passengerId}  String，乘车人占位
-- ARGV[1..] = 区段号（独立逐个传入）
--
-- 返回：
--   0  预检通过，可以进入事务消息链路
--  -1  某区段库存不足
--  -2  stock Hash/field 不存在（未预热）
--  -3  该乘车人已购买该车次车票
--
-- 注意：本脚本只做提前拦截，不承担防超卖职责；
-- 正式扣减仍以 deduct.lua 内的原子检查为最终闸门。

-- ① 乘车人判重
if redis.call('EXISTS', KEYS[2]) == 1 then
    return -3
end

-- ② 逐个区段检查库存，一条数据都不改
for i = 1, #ARGV do
    local v = redis.call('HGET', KEYS[1], ARGV[i])
    if v == false or v == nil then
        return -2
    end
    if tonumber(v) <= 0 then
        return -1
    end
end

return 0
