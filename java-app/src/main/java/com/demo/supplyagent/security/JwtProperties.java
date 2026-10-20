package com.demo.supplyagent.security;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * app.jwt.* 配置绑定。secret 只允许经环境变量 JWT_SECRET 注入;
 * 空或不足 32 字节(HS256 最低强度)由 JwtService 构造期抛错拒绝,绝不提供默认 secret。
 */
@Data
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {

    private String secret;

    private long expireHours = 24;
}
