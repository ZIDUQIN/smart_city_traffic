package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.BizException;
import com.smartcity.traffic.entity.TicketPriceRecord;
import com.smartcity.traffic.mapper.TicketPriceMapper;
import com.smartcity.traffic.service.PriceService;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 票价计算实现：按班次 + 起讫站序 + 席别查票价表。
 *
 * <p>缺价即拒绝下单（fail-close），绝不按区段数临时估算——
 * 估算出来的价钱出错比直接拒单更难处理。</p>
 */
@Service
public class PriceServiceImpl implements PriceService {

    private final TicketPriceMapper ticketPriceMapper;

    public PriceServiceImpl(TicketPriceMapper ticketPriceMapper) {
        this.ticketPriceMapper = ticketPriceMapper;
    }

    @Override
    public BigDecimal calculate(String trainNo, String seatNo, Integer fromSeq, Integer toSeq) {
        TicketPriceRecord record = ticketPriceMapper.selectPrice(trainNo, fromSeq, toSeq, seatNo);
        if (record == null || record.getPrice() == null) {
            throw new BizException("该行程暂未配置票价，无法下单");
        }
        return record.getPrice();
    }
}
