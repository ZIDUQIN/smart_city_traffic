-- 雪花算法 workerId 原子抢占脚本
-- KEYS[1]: worker key 前缀，例如 "snowflake:worker:"
-- ARGV[1]: 实例唯一标识 instanceId（用于排查与安全续期）
-- ARGV[2]: 每个 worker key 的过期时间（秒）
--
-- 逻辑：在一次脚本调用内（Redis 单线程执行，天然原子）遍历 0~1023，
-- 对每个 workerId 执行 SET NX EX：仅当 key 不存在时抢占成功并设置 TTL。
-- 第一个抢占成功的 workerId 立即返回；全部被占用则返回 -1。
for workerId = 0, 1023 do
    local key = KEYS[1] .. workerId
    local ok = redis.call('SET', key, ARGV[1], 'NX', 'EX', ARGV[2])
    if ok then
        return workerId
    end
end
return -1
