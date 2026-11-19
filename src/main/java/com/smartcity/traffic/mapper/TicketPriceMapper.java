package com.smartcity.traffic.mapper;

import com.smartcity.traffic.entity.TicketPriceRecord;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 票价 Mapper（表 ticket_price），模板层。
 *
 * <p>缺价策略是 fail-close：查不到直接拒绝下单，绝不临时估算。</p>
 */
public interface TicketPriceMapper {

    /** 按班次 + 起讫站序 + 座位等级查价 */
    TicketPriceRecord selectPrice(@Param("trainNo") String trainNo,
                                  @Param("fromSeq") Integer fromSeq,
                                  @Param("toSeq") Integer toSeq,
                                  @Param("seatNo") String seatNo);

    /** 查某班次全部票价，用于管理端展示与校验 */
    List<TicketPriceRecord> selectByTrainNo(@Param("trainNo") String trainNo);
}
