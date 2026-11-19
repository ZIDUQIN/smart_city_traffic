package com.smartcity.traffic.controller;

import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.common.CurrentUserHelper;
import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.dto.OrderRequest;
import com.smartcity.traffic.dto.OrderResponse;
import com.smartcity.traffic.dto.RefundResponse;
import com.smartcity.traffic.entity.DeductRecord;
import com.smartcity.traffic.entity.OrderRecord;
import com.smartcity.traffic.mapper.OrderMapper;
import com.smartcity.traffic.mapper.TrainMapper;
import com.smartcity.traffic.service.DeductRecordService;
import com.smartcity.traffic.service.OrderService;
import com.smartcity.traffic.service.PaymentService;
import com.smartcity.traffic.service.RefundService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 抢票下单接口（前缀 /api/orders）。
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orderService;
    private final DeductRecordService deductRecordService;
    private final OrderMapper orderMapper;
    private final TrainMapper trainMapper;
    private final PaymentService paymentService;
    private final RefundService refundService;
    private final CurrentUserHelper currentUserHelper;

    public OrderController(OrderService orderService,
                           DeductRecordService deductRecordService,
                           OrderMapper orderMapper,
                           TrainMapper trainMapper,
                           PaymentService paymentService,
                           RefundService refundService,
                           CurrentUserHelper currentUserHelper) {
        this.orderService = orderService;
        this.deductRecordService = deductRecordService;
        this.orderMapper = orderMapper;
        this.trainMapper = trainMapper;
        this.paymentService = paymentService;
        this.refundService = refundService;
        this.currentUserHelper = currentUserHelper;
    }

    /** 抢票：事务消息 + 本地事务四步，返回受理结果 */
    @PostMapping
    public Result<OrderResponse> create(@RequestBody OrderRequest request) {
        return Result.success(orderService.createOrder(request));
    }

    /**
     * 按订单号查状态（UNKNOWN 时前端轮询用）。
     * 流水 + 订单两张表都查，订单已确认以订单状态为准。
     */
    @GetMapping("/{orderNo}")
    public Result<OrderResponse> status(@PathVariable String orderNo) {
        DeductRecord record = deductRecordService.getByOrderNo(orderNo);
        if (record == null) {
            throw new BizException("订单不存在");
        }

        OrderResponse response = new OrderResponse();
        response.setOrderNo(orderNo);
        response.setPassengerId(record.getPassengerId());
        response.setTrainNo(record.getTrainNo());
        response.setSeatNo(record.getSeatNo());

        OrderRecord order = orderMapper.selectByOrderNo(orderNo);
        // 归属校验：读接口与写接口同口径，非本人一律按“不存在”处理，避免泄露他人订单
        Long ownerId = order != null ? order.getUserId() : record.getUserId();
        if (ownerId == null || !ownerId.equals(currentUserHelper.currentUserId())) {
            throw new BizException("订单不存在");
        }
        if (order != null) {
            response.setStatus(order.getStatus());
        } else {
            response.setStatus(record.getStatus());
            response.setMessage(record.getFailReason());
        }
        return Result.success(response);
    }

    /**
     * 统一预热（B2 新契约）：只传 trainNo，seatNo 可选，不再接收 remain。
     * 从 stock 表加载，先 DEL 旧 key 再写 stock Hash。
     */
    /**
     * 统一预热（实例版）：按班次 + 运营日期预热当天那一个实例的库存。
     * 内部用 HSETNX 补缺失字段，不会把已售余票覆盖回满票。
     */
    @PostMapping("/preheat")
    public Result<Integer> preheat(@RequestParam String trainNo,
                                   @RequestParam(required = false)
                                   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
                                   @RequestParam(required = false) String seatNo) {
        return Result.success(orderService.preheat(trainNo, travelDate, seatNo));
    }

    /** 用户主动取消待支付订单（回补 Redis/DB） */
    @PostMapping("/{orderNo}/cancel")
    public Result<Void> cancel(@PathVariable String orderNo) {
        paymentService.userCancel(orderNo);
        return Result.success(null);
    }

    /** 我的订单列表（只返回当前用户），补充上下车站名便于前端展示行程 */
    @GetMapping("/my")
    public Result<List<OrderRecord>> my() {
        List<OrderRecord> orders = orderMapper.selectByUserId(currentUserHelper.currentUserId());
        fillStationNames(orders);
        return Result.success(orders);
    }

    /**
     * 订单表不存站名，只有 fromSeq / toSeq。
     * 一次性把「车次 + 站序 → 站名」全量取回内存，再逐单回填，
     * 避免每条订单都去查一次数据库。
     */
    private void fillStationNames(List<OrderRecord> orders) {
        if (orders == null || orders.isEmpty()) {
            return;
        }
        Map<String, String> nameMap = new HashMap<>();
        for (Map<String, Object> row : trainMapper.selectAllStationSeqNames()) {
            Object seq = row.get("stationSeq");
            Object name = row.get("stationName");
            if (seq == null || name == null) {
                continue;
            }
            nameMap.put(row.get("trainNo") + ":" + seq, String.valueOf(name));
        }
        for (OrderRecord order : orders) {
            order.setFromStation(nameMap.get(order.getTrainNo() + ":" + order.getFromSeq()));
            order.setToStation(nameMap.get(order.getTrainNo() + ":" + order.getToSeq()));
        }
    }

    /**
     * 退票：仅已支付订单可用。
     * 顺序固定：事务内切状态 → 事务后回补库存 → 调渠道退款。
     */
    @PostMapping("/{orderNo}/refund")
    public Result<RefundResponse> refund(@PathVariable String orderNo,
                                         @RequestParam(required = false) String reason) {
        return Result.success(refundService.applyRefund(orderNo, reason));
    }

    /** 查询退款进度（前端轮询用） */
    @GetMapping("/{orderNo}/refund")
    public Result<RefundResponse> refundStatus(@PathVariable String orderNo) {
        return Result.success(refundService.status(orderNo));
    }
}
