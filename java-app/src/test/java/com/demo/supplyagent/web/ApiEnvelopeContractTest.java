package com.demo.supplyagent.web;

import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import javax.sql.DataSource;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 信封契约回归：所有响应（成功与 W1/W2/W3 既有错误）顶层恒为 {code, message, data} 恰三字段，
 * 不允许新增顶层 details 等字段——结构化补充（400 errors[]、409 状态等）只允许藏在 data 内。
 * 语义细节不在此类重复（见 ToolAcl/ChatProxy/各工具集成测试），这里只锁「形状」。
 * 全部只读：不含任何产生业务写入的请求。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ApiEnvelopeContractTest {

    private static final String LOGIN_URI = "/api/auth/login";
    private static final String CHAT_URI = "/api/chat";
    private static final String STOCK_URI = "/api/agent/tools/query-stock-level";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @Test
    void loginSuccessIsThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void w1ToolValidation400IsThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(post(STOCK_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 与 category 不能同时为空"))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void nullPurchaseLineIsBadRequestWithoutWrites() throws Exception {
        MvcResult result = mvc.perform(post("/api/agent/tools/create-purchase-order")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .header("Idempotency-Key", "null-line-contract-check")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"items\":[null]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("items 不能包含空条目"))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void unreadableBody400IsThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(post(CHAT_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void anonymous401IsThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(post(CHAT_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void viewer403IsThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(post("/api/agent/tools/create-purchase-order")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token("viewer", "viewer123"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void unknownTool404IsThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(post("/api/agent/tools/not-a-tool")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + adminToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    // ---------- helpers ----------

    /** 顶层恰为 {code,message,data} 三字段;不允许新增顶层 details 之类的补充字段。 */
    private void assertThreeFieldEnvelope(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(root.size()).isEqualTo(3);
        Set<String> fields = new HashSet<>();
        root.fieldNames().forEachRemaining(fields::add);
        assertThat(fields).containsExactlyInAnyOrder("code", "message", "data");
    }

    private String adminToken() throws Exception {
        return token("admin", "admin123");
    }

    private String token(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}
