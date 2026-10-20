package com.demo.supplyagent.security;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jws;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2-D1 安全语义集成测试(唯一验证真实 JWT 的地方):
 * 完整 SecurityFilterChain + 真实 test 库 seed 账号(admin/viewer,演示口令仅测试环境使用)。
 * 覆盖:登录成功/凭证错(同文案防枚举)/空参/无 token/伪造/过期/Bearer 格式错/
 * 匿名白名单/合法 token 过链/claims 语义/日志不泄密。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuthFlowIntegrationTest {

    private static final String LOGIN_URI = "/api/auth/login";
    private static final String STOCK_TOOL_URI = "/api/agent/tools/query-stock-level";
    private static final String LOGIN_FAILED_MESSAGE = "用户名或密码错误";
    private static final String MSG_MISSING = "未登录或登录已过期";
    private static final String MSG_INVALID = "token 无效";
    private static final String MSG_EXPIRED = "登录已过期,请重新登录";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    // ---------- 登录成功 ----------

    @Test
    void adminLoginReturnsTokenAndUniformEnvelope() throws Exception {
        MvcResult result = login("admin", "admin123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(24 * 3600L))
                .andExpect(jsonPath("$.data.role").value("admin"))
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andReturn();
        String token = readToken(result);
        assertThat(jwtService.parse(token)).isEqualTo(new AuthUser("admin", "admin"));
    }

    @Test
    void viewerLoginReturnsViewerRole() throws Exception {
        MvcResult result = login("viewer", "viewer123")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("viewer"))
                .andReturn();
        assertThat(jwtService.parse(readToken(result))).isEqualTo(new AuthUser("viewer", "viewer"));
    }

    // ---------- 登录失败 ----------

    @Test
    void wrongPasswordReturnsHttp401WithCode401() throws Exception {
        login("admin", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(LOGIN_FAILED_MESSAGE))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void unknownAccountSameCodeAndSameMessageAsWrongPassword() throws Exception {
        login("no-such-user", "whatever")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(LOGIN_FAILED_MESSAGE));
    }

    @Test
    void blankCredentialsRejectedWith400() throws Exception {
        mvc.perform(post(LOGIN_URI).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post(LOGIN_URI).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"\",\"password\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        mvc.perform(post(LOGIN_URI).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    // ---------- 受保护接口认证失败 ----------

    @Test
    void missingTokenOnBusinessApiReturns401() throws Exception {
        callStockToolWithoutToken()
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(MSG_MISSING));
    }

    @Test
    void forgedTokenReturns401() throws Exception {
        String token = loginToken("admin", "admin123");
        // 翻转签名段中部整字节位(base64 末位含 padding,末位翻转可能解码相同字节而漏检)
        int secondDot = token.indexOf('.', token.indexOf('.') + 1);
        int idx = secondDot + 3;
        char c = token.charAt(idx);
        String forged = token.substring(0, idx) + (c == 'a' ? 'b' : 'a') + token.substring(idx + 1);
        callStockToolWithToken(forged)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(MSG_INVALID));
    }

    @Test
    void expiredTokenReturns401WithExpiredMessage() throws Exception {
        String expired = buildExpiredToken("admin", "admin");
        callStockToolWithToken(expired)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value(MSG_EXPIRED));
    }

    @Test
    void malformedAuthorizationHeaderReturns401() throws Exception {
        String stockBody = "{\"skus\":[\"AUD-001\"]}";
        mvc.perform(post(STOCK_TOOL_URI).contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody)
                        .header(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(post(STOCK_TOOL_URI).contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody)
                        .header(HttpHeaders.AUTHORIZATION, "not-a-bearer-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        mvc.perform(post(STOCK_TOOL_URI).contentType(MediaType.APPLICATION_JSON)
                        .content(stockBody)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer "))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- 匿名白名单 ----------

    @Test
    void healthIsAnonymous() throws Exception {
        mvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void loginIsAnonymousReachable() throws Exception {
        // 与 adminLoginReturnsTokenAndUniformEnvelope 同请求形态:全程无 Authorization 头即可达
        login("viewer", "viewer123").andExpect(status().isOk());
    }

    // ---------- 合法 token 全链通过 ----------

    @Test
    void validTokenPassesThroughFullSecurityFilterChain() throws Exception {
        String token = loginToken("admin", "admin123");
        callStockToolWithToken(token)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"));
    }

    @Test
    void tokenClaimsCarryExpectedSemantics() throws Exception {
        String token = loginToken("admin", "admin123");
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        Jws<Claims> jws = Jwts.parser().verifyWith(key).build().parseSignedClaims(token);
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        Claims claims = jws.getPayload();
        assertThat(claims.getSubject()).isEqualTo("admin");
        assertThat(claims.get("role", String.class)).isEqualTo("admin");
        assertThat(claims.getExpiration().getTime() - claims.getIssuedAt().getTime())
                .isEqualTo(24 * 3600 * 1000L);
    }

    // ---------- 日志不泄密 ----------

    @Test
    void logsNeverContainPasswordFullTokenOrSecret() throws Exception {
        Logger projectLogger = (Logger) LoggerFactory.getLogger("com.demo.supplyagent");
        Logger securityLogger = (Logger) LoggerFactory.getLogger("org.springframework.security");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        projectLogger.addAppender(appender);
        securityLogger.addAppender(appender);
        try {
            login("admin", "admin123").andExpect(status().isOk());
            String token = loginToken("viewer", "viewer123");
            login("admin", "wrong-password").andExpect(status().isUnauthorized());
            callStockToolWithToken(token.substring(0, token.length() - 1) + "x")
                    .andExpect(status().isUnauthorized());
        } finally {
            projectLogger.detachAppender(appender);
            securityLogger.detachAppender(appender);
        }
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs)
                .doesNotContain("admin123", "viewer123")
                .doesNotContain(jwtProperties.getSecret());
        for (ILoggingEvent event : appender.list) {
            assertThat(event.getFormattedMessage()).doesNotContain("Bearer ");
        }
    }

    // ---------- helpers ----------

    private ResultActions login(String username, String password) throws Exception {
        return mvc.perform(post(LOGIN_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))));
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult result = login(username, password)
                .andExpect(status().isOk())
                .andReturn();
        return readToken(result);
    }

    private String readToken(MvcResult result) throws Exception {
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).get("data");
        return data.get("token").asText();
    }

    private ResultActions callStockToolWithoutToken() throws Exception {
        return mvc.perform(post(STOCK_TOOL_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skus\":[\"AUD-001\"]}"));
    }

    private ResultActions callStockToolWithToken(String token) throws Exception {
        return mvc.perform(post(STOCK_TOOL_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"skus\":[\"AUD-001\"]}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private String buildExpiredToken(String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now.minusSeconds(7200)))
                .expiration(Date.from(now.minusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
