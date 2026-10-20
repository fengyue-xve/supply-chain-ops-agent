package com.demo.supplyagent;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.demo.supplyagent.mapper")
public class SupplyAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplyAgentApplication.class, args);
    }
}
