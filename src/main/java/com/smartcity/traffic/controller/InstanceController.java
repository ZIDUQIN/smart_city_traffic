package com.smartcity.traffic.controller;

import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.entity.TrainInstanceRecord;
import com.smartcity.traffic.service.InstanceService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 班次实例窗口管理接口（前缀 /api/instances）。
 * 定时任务已经会自动补齐，这里的接口用于手工排查与演示。
 */
@RestController
@RequestMapping("/api/instances")
public class InstanceController {

    private final InstanceService instanceService;

    public InstanceController(InstanceService instanceService) {
        this.instanceService = instanceService;
    }

    /** 查某班次未来 days 天的实例状态，缺的日期返回 null */
    @GetMapping("/window")
    public Result<List<TrainInstanceRecord>> window(@RequestParam String trainNo,
                                                    @RequestParam(defaultValue = "7") int days) {
        return Result.success(instanceService.windowState(trainNo, days));
    }

    /** 手工补齐窗口（幂等） */
    @PostMapping("/ensure")
    public Result<Integer> ensure(@RequestParam(defaultValue = "7") int days) {
        return Result.success(instanceService.ensureWindow(days));
    }

    /** 手工预热某个实例 */
    @PostMapping("/{instanceNo}/preheat")
    public Result<Boolean> preheat(@PathVariable String instanceNo) {
        return Result.success(instanceService.preheatInstance(instanceNo));
    }

    /** 手工清理过期实例 */
    @PostMapping("/cleanup")
    public Result<Integer> cleanup(@RequestParam(defaultValue = "2") int delayDays) {
        return Result.success(instanceService.cleanupExpired(delayDays));
    }
}
