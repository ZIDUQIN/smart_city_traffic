package com.smartcity.traffic.controller;

import com.smartcity.traffic.common.Result;
import com.smartcity.traffic.dto.RemainResponse;
import com.smartcity.traffic.dto.StationVO;
import com.smartcity.traffic.dto.TrainSearchVO;
import com.smartcity.traffic.service.TicketService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * 车票查询接口（前缀 /api/ticket）。
 */
@RestController
@RequestMapping("/api/ticket")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    /**
     * 查询余票（读统一库存）。
     * 例：/api/ticket/remain?trainNo=G1001&fromSeq=1&toSeq=3&seatNo=SECOND_CLASS
     */
    @GetMapping("/remain")
    public Result<RemainResponse> remain(@RequestParam String trainNo,
                                         @RequestParam(required = false)
                                         @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate,
                                         @RequestParam Integer fromSeq,
                                         @RequestParam Integer toSeq,
                                         @RequestParam String seatNo) {
        return Result.success(ticketService.queryRemain(trainNo, travelDate, fromSeq, toSeq, seatNo));
    }

    /**
     * 全部车站，供前端出发/到达站下拉。
     */
    @GetMapping("/stations")
    public Result<List<StationVO>> stations() {
        return Result.success(ticketService.stations());
    }

    /**
     * 按出发站 + 到达站 + 日期查车次列表。
     * 例：/api/ticket/search?fromStation=西安北&toStation=北京西&travelDate=2026-11-17
     */
    @GetMapping("/search")
    public Result<List<TrainSearchVO>> search(@RequestParam String fromStation,
                                              @RequestParam String toStation,
                                              @RequestParam(required = false)
                                              @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate travelDate) {
        return Result.success(ticketService.search(fromStation, toStation, travelDate));
    }
}
