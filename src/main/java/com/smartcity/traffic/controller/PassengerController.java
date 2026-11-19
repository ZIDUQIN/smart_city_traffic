package com.smartcity.traffic.controller;

import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.dto.PassengerRequest;
import com.smartcity.traffic.dto.PassengerVO;
import com.smartcity.traffic.service.PassengerService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 乘车人管理接口（RESTful，前缀 /api/passengers）。
 * 所有接口都需要登录，且只能操作自己名下的乘车人。
 */
@RestController
@RequestMapping("/api/passengers")
public class PassengerController {

    private final PassengerService passengerService;

    public PassengerController(PassengerService passengerService) {
        this.passengerService = passengerService;
    }

    /** 新增乘车人 */
    @PostMapping
    public Result<PassengerVO> create(@RequestBody PassengerRequest request) {
        return Result.success(passengerService.create(request));
    }

    /** 查询当前用户的乘车人列表 */
    @GetMapping
    public Result<List<PassengerVO>> list() {
        return Result.success(passengerService.list());
    }

    /** 修改乘车人 */
    @PutMapping("/{id}")
    public Result<PassengerVO> update(@PathVariable Long id, @RequestBody PassengerRequest request) {
        return Result.success(passengerService.update(id, request));
    }

    /** 删除乘车人 */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable Long id) {
        passengerService.delete(id);
        return Result.success();
    }
}
