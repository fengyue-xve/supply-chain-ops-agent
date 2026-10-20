package com.demo.flowagent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "flow")
public record FlowProperties(String fixturesDir) {
}
