package com.demo.supplyagent.web;

import com.demo.supplyagent.security.JwtProperties;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
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

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2-D2 / W3-③ 工具级 ACL 集成测试(完整 SecurityFilterChain + 真实 test 库 seed 账号):
 * 匿名对全部工具入口 401;viewer 可执行三只读工具 + 发现端点,但写工具(readOnly=false)一律 403;
 * admin 全放;未注册工具名保持 404 语义(不误判 403);伪造/过期 token 仍是 401。
 * viewer 的 403 先于 Controller 校验:同一畸形请求 viewer=403、admin=400,证明拦截器在前。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ToolAclIntegrationTest {

    private static final String LOGIN_URI = "/api/auth/login";
    private static final String STOCK_URI = "/api/agent/tools/query-stock-level";
    private static final String SALES_URI = "/api/agent/tools/query-sales";
    private static final String SUGGEST_URI = "/api/agent/tools/suggest-replenish";
    private static final String CREATE_URI = "/api/agent/tools/create-purchase-order";
    private static final String CONFIRM_URI = "/api/agent/tools/confirm-po";
    private static final String KEY_HEADER = "Idempotency-Key";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtProperties jwtProperties;

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    // ---------- 匿名一律 401 ----------

    @Test
    void anonymousToolDiscoveryListIs401() throws Exception {
        mvc.perform(get("/api/agent/tools"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void anonymousToolSchemaIs401() throws Exception {
        mvc.perform(get("/api/agent/tools/schema"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void anonymousStockToolIs401() throws Exception {
        callTool(STOCK_URI, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    // ---------- viewer 只读全放 ----------

    @Test
    void viewerCanRunStockLevelTool() throws Exception {
        callTool(STOCK_URI, token("viewer", "viewer123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"));
    }

    @Test
    void viewerCanRunSalesTool() throws Exception {
        callTool(SALES_URI, token("viewer", "viewer123"), "{\"sku\":\"AUD-001\",\"days\":7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").exists());
    }

    @Test
    void viewerCanRunSuggestReplenishTool() throws Exception {
        callTool(SUGGEST_URI, token("viewer", "viewer123"), "{\"skus\":[\"AUD-001\"],\"days\":7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void viewerCanReadDiscoveryEndpoints() throws Exception {
        mvc.perform(get("/api/agent/tools").header(HttpHeaders.AUTHORIZATION, bearer("viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.tools[3].readOnly").value(false));
        mvc.perform(get("/api/agent/tools/schema").header(HttpHeaders.AUTHORIZATION, bearer("viewer")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- 写工具:viewer 403(先于 Controller 校验),admin 放行后按 400 校验 ----------

    @Test
    void anonymousWriteToolsAre401() throws Exception {
        callTool(CREATE_URI, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        callTool(CONFIRM_URI, null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void viewerWriteToolsAre403EvenWithMalformedBody() throws Exception {
        // 畸形体 + viewer:403 而非 400,证明 ACL 拦截先于参数校验
        callTool(CREATE_URI, token("viewer", "viewer123"), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));
        callTool(CONFIRM_URI, token("viewer", "viewer123"), "{}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));
    }

    @Test
    void adminPassesAclOnWriteToolsThenHitsValidation() throws Exception {
        // admin 通过 ACL 后落入 Controller 校验:400,且零写库(畸形体不产生任何行)
        var createRequest = post(CREATE_URI).contentType(MediaType.APPLICATION_JSON)
                .content("{\"items\":[]}")
                .header(HttpHeaders.AUTHORIZATION, bearer("admin"))
                .header(KEY_HEADER, "it-acl-create-1");
        mvc.perform(createRequest)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("items 不能为空"));
        callTool(CONFIRM_URI, token("admin", "admin123"), "{\"poNo\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("poNo 不能为空"));
    }

    // ---------- admin 三只读工具全放 ----------

    @Test
    void adminCanRunAllThreeReadOnlyTools() throws Exception {
        callTool(STOCK_URI, token("admin", "admin123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        callTool(SALES_URI, token("admin", "admin123"), "{\"sku\":\"AUD-001\",\"days\":7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        callTool(SUGGEST_URI, token("admin", "admin123"), "{\"skus\":[\"AUD-001\"],\"days\":7}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- 未注册工具名:404 语义,不是 403 ----------

    @Test
    void viewerUnknownToolNameStays404Not403() throws Exception {
        callTool("/api/agent/tools/delete-purchase-order", token("viewer", "viewer123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void adminUnknownToolNameStays404Not403() throws Exception {
        callTool("/api/agent/tools/cancel-purchase-order", token("admin", "admin123"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404));
    }

    // ---------- 坏 token 仍是 401,不漂移成 403 ----------

    @Test
    void forgedTokenStill401Not403() throws Exception {
        String token = token("viewer", "viewer123");
        // 翻转签名段中部整字节位(base64 末位含 padding,末位翻转可能解码相同字节而漏检)
        int secondDot = token.indexOf('.', token.indexOf('.') + 1);
        int idx = secondDot + 3;
        char c = token.charAt(idx);
        String forged = token.substring(0, idx) + (c == 'a' ? 'b' : 'a') + token.substring(idx + 1);
        callTool(STOCK_URI, forged)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("token 无效"));
    }

    @Test
    void expiredTokenStill401() throws Exception {
        String expired = buildExpiredToken("viewer", "viewer");
        callTool(STOCK_URI, expired)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("登录已过期,请重新登录"));
    }

    // ---------- helpers ----------

    private String bearer(String username) throws Exception {
        return "Bearer " + token(username, username + "123");
    }

    private String token(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString()).get("data");
        return data.get("token").asText();
    }

    private ResultActions callTool(String uri, String token) throws Exception {
        return callTool(uri, token, "{\"skus\":[\"AUD-001\"]}");
    }

    private ResultActions callTool(String uri, String token, String body) throws Exception {
        var request = post(uri).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
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
