package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.StockKeys;
import com.smartcity.traffic.service.RedisLuaService;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis Lua 服务实现。
 * 所有 Redis 库存操作都走 Lua，禁止在 Java 里直接 HINCRBY/INCR。
 *
 * key 里用 {instanceNo} 做 hash tag：同一车次的 stock/deducted/passenger 三个 key
 * 都落在同一个 slot，Lua 多 key 操作不会报 CROSSSLOT。
 */
@Service
public class RedisLuaServiceImpl implements RedisLuaService {

    private final StringRedisTemplate redisTemplate;

    private final DefaultRedisScript<Long> checkScript;

    private final DefaultRedisScript<Long> deductScript;

    private final DefaultRedisScript<Long> rollbackScript;

    public RedisLuaServiceImpl(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;

        checkScript = new DefaultRedisScript<>();
        checkScript.setLocation(new ClassPathResource("lua/check.lua"));
        checkScript.setResultType(Long.class);

        deductScript = new DefaultRedisScript<>();
        deductScript.setLocation(new ClassPathResource("lua/deduct.lua"));
        deductScript.setResultType(Long.class);

        rollbackScript = new DefaultRedisScript<>();
        rollbackScript.setLocation(new ClassPathResource("lua/rollback.lua"));
        rollbackScript.setResultType(Long.class);
    }

    @Override
    public int check(String instanceNo, String seatNo, Long passengerId,
                     List<Integer> segments) {
        // 预检只读：只传 stock 与乘车人两个 key，ARGV 只有区段号
        Long result = redisTemplate.execute(checkScript,
                List.of(stockKey(instanceNo, seatNo),
                        passengerKey(instanceNo, passengerId)),
                buildSegmentArgs(segments));
        return result == null ? -2 : result.intValue();
    }

    @Override
    public long deduct(String instanceNo, String seatNo, Long passengerId,
                       String orderNo, List<Integer> segments) {
        Long result = redisTemplate.execute(deductScript,
                List.of(stockKey(instanceNo, seatNo),
                        deductedKey(instanceNo, seatNo),
                        passengerKey(instanceNo, passengerId)),
                buildArgs(orderNo, segments));
        return result == null ? -2L : result;
    }

    @Override
    public int rollback(String instanceNo, String seatNo, Long passengerId,
                        String orderNo, List<Integer> segments) {
        Long result = redisTemplate.execute(rollbackScript,
                List.of(stockKey(instanceNo, seatNo),
                        deductedKey(instanceNo, seatNo),
                        passengerKey(instanceNo, passengerId)),
                buildArgs(orderNo, segments));
        return result == null ? 0 : result.intValue();
    }

    @Override
    public boolean isDeducted(String instanceNo, String seatNo, String orderNo) {
        Boolean member = redisTemplate.opsForSet().isMember(deductedKey(instanceNo, seatNo), orderNo);
        return Boolean.TRUE.equals(member);
    }

    /**
     * 组装 Lua 参数：ARGV[1]=orderNo，之后每个区段号独立一个 ARGV。
     * 禁止把区段拼成逗号字符串，否则 Lua 只会收到一个 ARGV。
     */
    private Object[] buildArgs(String orderNo, List<Integer> segments) {
        List<Object> args = new ArrayList<>(segments.size() + 1);
        args.add(orderNo);
        for (Integer segment : segments) {
            args.add(String.valueOf(segment));
        }
        return args.toArray();
    }

    /**
     * 预检专用参数：只有区段号，没有 orderNo（check.lua 不接收订单号）。
     */
    private Object[] buildSegmentArgs(List<Integer> segments) {
        List<Object> args = new ArrayList<>(segments.size());
        for (Integer segment : segments) {
            args.add(String.valueOf(segment));
        }
        return args.toArray();
    }

    /** stock:{instanceNo}:{seatNo}（花括号为真实 hash tag），Hash 类型 */
    private String stockKey(String instanceNo, String seatNo) {
        return StockKeys.stock(instanceNo, seatNo);
    }

    /** deducted:{instanceNo}:{seatNo} */
    private String deductedKey(String instanceNo, String seatNo) {
        return StockKeys.deducted(instanceNo, seatNo);
    }

    /** passenger:dedup:{instanceNo}:{passengerId} */
    private String passengerKey(String instanceNo, Long passengerId) {
        return StockKeys.passenger(instanceNo, passengerId);
    }
}
