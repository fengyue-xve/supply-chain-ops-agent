package com.demo.supplyagent.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W2-D1 JwtService 纯单测(无 Spring、无库):
 * 签发/解析往返、过期、篡改、缺 role/未知 role/空 sub 一律拒、弱 secret 构造失败。
 */
class JwtServiceTest {

    private static final String TEST_SECRET =
            "unit-test-jwt-secret-0123456789abcdef0123456789abcdef";  // 52B ≥ 32
    private static final String WEAK_SECRET = "this-secret-is-short-0123";  // 25B < 32,须被拒

    private JwtService newService(String secret) {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(secret);
        properties.setExpireHours(24);
        return new JwtService(properties);
    }

    private String signWithRawBuilder(String subject, String role, Instant exp, String secret) {
        SecretKey key = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
        io.jsonwebtoken.JwtBuilder builder = Jwts.builder()
                .subject(subject)
                .issuedAt(Date.from(Instant.now().minusSeconds(60)));
        if (role != null) {
            builder.claim("role", role);
        }
        return builder.expiration(Date.from(exp)).signWith(key, Jwts.SIG.HS256).compact();
    }

    @Test
    void signThenParseRoundTrips() {
        JwtService service = newService(TEST_SECRET);
        String token = service.sign("admin", "admin").value();
        assertThat(service.parse(token)).isEqualTo(new AuthUser("admin", "admin"));
    }

    @Test
    void viewerRoleRoundTrips() {
        JwtService service = newService(TEST_SECRET);
        String token = service.sign("viewer", "viewer").value();
        assertThat(service.parse(token)).isEqualTo(new AuthUser("viewer", "viewer"));
    }

    @Test
    void expiredTokenRejectedWithExpiredReason() {
        JwtService service = newService(TEST_SECRET);
        String token = signWithRawBuilder("admin", "admin", Instant.now().minusSeconds(3600), TEST_SECRET);
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(JwtAuthException.class)
                .extracting(e -> ((JwtAuthException) e).getReason())
                .isEqualTo(JwtAuthException.Reason.EXPIRED);
    }

    @Test
    void tamperedSignatureRejectedWithInvalidReason() {
        JwtService service = newService(TEST_SECRET);
        String token = service.sign("admin", "admin").value();
        // 注:不能在末位翻转——base64 最后一位含 padding 位,'a'/'b' 翻转可能解码出相同字节(实测间歇性漏检);
        // 改为翻转签名段第 3 个字符(整字节位,改动必破坏 HMAC)。
        int secondDot = token.indexOf('.', token.indexOf('.') + 1);
        int idx = secondDot + 3;
        char c = token.charAt(idx);
        String tampered = token.substring(0, idx) + (c == 'a' ? 'b' : 'a') + token.substring(idx + 1);
        assertThatThrownBy(() -> service.parse(tampered))
                .isInstanceOf(JwtAuthException.class)
                .extracting(e -> ((JwtAuthException) e).getReason())
                .isEqualTo(JwtAuthException.Reason.INVALID);
    }

    @Test
    void missingRoleRejected() {
        JwtService service = newService(TEST_SECRET);
        String token = signWithRawBuilder("admin", null, Instant.now().plusSeconds(3600), TEST_SECRET);
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(JwtAuthException.class)
                .extracting(e -> ((JwtAuthException) e).getReason())
                .isEqualTo(JwtAuthException.Reason.INVALID);
    }

    @Test
    void unknownRoleRejected() {
        JwtService service = newService(TEST_SECRET);
        String token = signWithRawBuilder("admin", "superadmin", Instant.now().plusSeconds(3600), TEST_SECRET);
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(JwtAuthException.class)
                .extracting(e -> ((JwtAuthException) e).getReason())
                .isEqualTo(JwtAuthException.Reason.INVALID);
    }

    @Test
    void blankSubjectRejected() {
        JwtService service = newService(TEST_SECRET);
        String token = signWithRawBuilder("  ", "admin", Instant.now().plusSeconds(3600), TEST_SECRET);
        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(JwtAuthException.class)
                .extracting(e -> ((JwtAuthException) e).getReason())
                .isEqualTo(JwtAuthException.Reason.INVALID);
    }

    @Test
    void issuedTokenExpiresAfterConfiguredHours() {
        JwtProperties properties = new JwtProperties();
        properties.setSecret(TEST_SECRET);
        properties.setExpireHours(24);
        JwtService.IssuedToken issued = new JwtService(properties).sign("admin", "admin");
        assertThat(issued.expiresInSeconds()).isEqualTo(24 * 3600L);
    }

    @Test
    void missingSecretFailsAtConstruction() {
        assertThatThrownBy(() -> newService(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> newService("   ")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void weakSecretFailsAtConstruction() {
        assertThatThrownBy(() -> newService(WEAK_SECRET)).isInstanceOf(IllegalStateException.class);
    }
}
