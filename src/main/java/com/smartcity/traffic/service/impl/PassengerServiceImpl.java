package com.smartcity.traffic.service.impl;

import com.smartcity.traffic.common.CurrentUserHelper;
import com.smartcity.traffic.common.SnowflakeIdGenerator;
import com.smartcity.traffic.dto.PassengerRequest;
import com.smartcity.traffic.dto.PassengerVO;
import com.smartcity.traffic.entity.Passenger;
import com.smartcity.traffic.mapper.PassengerMapper;
import com.smartcity.traffic.service.PassengerService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 乘车人业务实现。
 */
@Service
public class PassengerServiceImpl implements PassengerService {

    /** 身份证号：18 位，最后一位可为 X */
    private static final Pattern ID_CARD_PATTERN = Pattern.compile("^[1-9]\\d{16}[\\dXx]$");

    private final PassengerMapper passengerMapper;
    private final SnowflakeIdGenerator snowflakeIdGenerator;
    private final CurrentUserHelper currentUserHelper;

    public PassengerServiceImpl(PassengerMapper passengerMapper,
                                SnowflakeIdGenerator snowflakeIdGenerator,
                                CurrentUserHelper currentUserHelper) {
        this.passengerMapper = passengerMapper;
        this.snowflakeIdGenerator = snowflakeIdGenerator;
        this.currentUserHelper = currentUserHelper;
    }

    @Override
    public PassengerVO create(PassengerRequest request) {
        Long userId = currentUserHelper.currentUserId();
        validate(request);

        // 同一用户下身份证号不可重复
        if (passengerMapper.selectByUserIdAndIdCard(userId, request.getIdCard()) != null) {
            throw new IllegalArgumentException("该身份证号已存在乘车人，请勿重复添加");
        }

        Passenger passenger = new Passenger();
        passenger.setId(snowflakeIdGenerator.nextId());
        passenger.setUserId(userId);
        passenger.setPassengerName(request.getPassengerName().trim());
        passenger.setIdCard(request.getIdCard().trim().toUpperCase());
        passenger.setPassengerType(request.getPassengerType());

        passengerMapper.insert(passenger);
        return toVO(passenger);
    }

    @Override
    public List<PassengerVO> list() {
        Long userId = currentUserHelper.currentUserId();
        List<Passenger> passengers = passengerMapper.selectByUserId(userId);
        List<PassengerVO> result = new ArrayList<>(passengers.size());
        for (Passenger p : passengers) {
            result.add(toVO(p));
        }
        return result;
    }

    @Override
    public PassengerVO update(Long id, PassengerRequest request) {
        Long userId = currentUserHelper.currentUserId();
        validate(request);

        Passenger existing = passengerMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("乘车人不存在");
        }
        // 只能操作属于当前用户的乘车人
        if (!userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("无权操作该乘车人");
        }

        // 改身份证号时校验是否与同用户下其他乘车人重复
        Passenger sameIdCard = passengerMapper.selectByUserIdAndIdCard(userId, request.getIdCard());
        if (sameIdCard != null && !sameIdCard.getId().equals(id)) {
            throw new IllegalArgumentException("该身份证号已存在乘车人，请勿重复添加");
        }

        existing.setPassengerName(request.getPassengerName().trim());
        existing.setIdCard(request.getIdCard().trim().toUpperCase());
        existing.setPassengerType(request.getPassengerType());
        passengerMapper.update(existing);
        return toVO(existing);
    }

    @Override
    public void delete(Long id) {
        Long userId = currentUserHelper.currentUserId();
        Passenger existing = passengerMapper.selectById(id);
        if (existing == null) {
            throw new IllegalArgumentException("乘车人不存在");
        }
        if (!userId.equals(existing.getUserId())) {
            throw new IllegalArgumentException("无权操作该乘车人");
        }
        passengerMapper.deleteById(id);
    }

    /** 参数校验：姓名必填、身份证格式合法、类型合法 */
    private void validate(PassengerRequest request) {
        if (request.getPassengerName() == null || request.getPassengerName().isBlank()) {
            throw new IllegalArgumentException("乘车人姓名不能为空");
        }
        if (request.getIdCard() == null || !ID_CARD_PATTERN.matcher(request.getIdCard().trim()).matches()) {
            throw new IllegalArgumentException("身份证号格式不正确");
        }
        Integer type = request.getPassengerType();
        if (type == null || type < 1 || type > 3) {
            throw new IllegalArgumentException("乘车人类型只能是 1 成人、2 学生、3 儿童");
        }
    }

    /** 实体转 VO，身份证号脱敏 */
    private PassengerVO toVO(Passenger passenger) {
        PassengerVO vo = new PassengerVO();
        vo.setId(passenger.getId());
        vo.setPassengerName(passenger.getPassengerName());
        vo.setIdCard(maskIdCard(passenger.getIdCard()));
        vo.setPassengerType(passenger.getPassengerType());
        return vo;
    }

    /** 保留前 4 位和后 4 位，中间用 * 代替 */
    private String maskIdCard(String idCard) {
        if (idCard == null || idCard.length() <= 8) {
            return idCard;
        }
        int maskLength = idCard.length() - 8;
        return idCard.substring(0, 4) + "*".repeat(maskLength) + idCard.substring(idCard.length() - 4);
    }
}
