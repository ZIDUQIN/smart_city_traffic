package com.smartcity.traffic;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@MapperScan("com.smartcity.traffic.mapper")
@EnableScheduling
public class SmartCityTrafficApplication {

    public static void main(String[] args) {
        SpringApplication.run(SmartCityTrafficApplication.class, args);
    }

}
