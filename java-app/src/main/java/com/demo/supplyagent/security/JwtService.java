package com.demo.supplyagent.security;

import com.demo.supplyagent.security.JwtAuthException.Reason;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.security.Keys;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

/**
 * JWT HS256 签发与解析唯一门面。
 * 签发:sub=username、role、iat、exp(默认 24h)。
 * 解析严格校验:签名、算法必须为 HS256、exp 未过期、sub 非空、role ∈ {admin, viewer};
 * 任一不满足抛 JwtAuthException,不进 SecurityContext。本类不写任何日志。
 */
public class JwtService {

    private static final String ALG_HS256 = "HS256";
    private static final String CLAIM_ROLE = "role";
    private static final int SECRET_MIN_BYTES = 32;

    private final SecretKey key;
    private final long expireSeconds;

    public JwtService(JwtProperties properties) {
        String secret = properties.getSecret();
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException(
                    "app.jwt.secret 未配置:JWT_SECRET 环境变量缺失或为空,拒绝启动(不提供默认 secret)");
        }
        byte[] secretBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < SECRET_MIN_BYTES) {
            throw new IllegalStateException(
                    "app.jwt.secret 长度不足 " + SECRET_MIN_BYTES + " 字节,HS256 拒绝弱密钥,请更换 JWT_SECRET");
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
        this.expireSeconds = properties.getExpireHours() * 3600L;
    }

    /** 签发结果:token 原文 + 有效期秒数(供登录响应直接组装)。 */
    public record IssuedToken(String value, long expiresInSeconds) {
    }

    public IssuedToken sign(String username, String role) {
        Instant now = Instant.now();
        String token = Jwts.builder()
                .subject(username)
                .claim(CLAIM_ROLE, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(expireSeconds)))
                // 显式钉死 HS256:secret 超 384bit 时 jjwt 默认按最长兼容算法签(HS384),会导致自签自拒
                .signWith(key, Jwts.SIG.HS256)
                .compact();
        return new IssuedToken(token, expireSeconds);
    }

    public AuthUser parse(String rawToken) {
        Jws<Claims> jws;
        try {
            jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(rawToken);
        } catch (ExpiredJwtException e) {
            throw new JwtAuthException(Reason.EXPIRED, "登录已过期,请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtAuthException(Reason.INVALID, "token 无效");
        }

        if (!ALG_HS256.equals(jws.getHeader().getAlgorithm())) {
            throw new JwtAuthException(Reason.INVALID, "token 无效");
        }

        String username;
        String role;
        try {
            username = jws.getPayload().getSubject();
            role = jws.getPayload().get(CLAIM_ROLE, String.class);
        } catch (JwtException | IllegalArgumentException e) {
            throw new JwtAuthException(Reason.INVALID, "token 无效");
        }
        if (username == null || username.isBlank()) {
            throw new JwtAuthException(Reason.INVALID, "token 无效");
        }
        if (!AuthUser.ROLE_ADMIN.equals(role) && !AuthUser.ROLE_VIEWER.equals(role)) {
            throw new JwtAuthException(Reason.INVALID, "token 无效");
        }
        return new AuthUser(username, role);
    }
}
