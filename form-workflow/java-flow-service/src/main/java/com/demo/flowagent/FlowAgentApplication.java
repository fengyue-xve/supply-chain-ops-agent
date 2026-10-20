package com.demo.flowagent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FlowAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(FlowAgentApplication.class, args);
    }
}
