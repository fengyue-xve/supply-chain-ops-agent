package com.demo.supplyagent.web;

import com.demo.supplyagent.gateway.AgentChatClient;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4-1 会话历史查询集成测试(真实 SecurityFilterChain + 真实 AgentConversationService + 真实 test 库,
 * 不经 ChatService/上游)。覆盖:匿名 401 / 空体 400 / 空与超长 conversation_id 400 / 分页参数 400 /
 * 本人历史时间正序与字段 / 分页切页 / 跨用户 403 不泄内容 / NULL 旧行不读出也不阻塞 /
 * 未知会话空列表 / 该端点绝不触达上游。
 * 本类产生的行由 @AfterEach 清理(it-conv-%),不影响 SchemaSeedSmokeTest 的种子计数断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatHistoryIntegrationTest {

    private static final String HISTORY_URI = "/api/chat/history";
    private static final String LOGIN_URI = "/api/auth/login";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private AgentChatClient agentChatClient;

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @AfterEach
    void cleanConversationRows() {
        jdbc.update("DELETE FROM agent_conversation WHERE session_id LIKE 'it-conv-%'");
    }

    // ---------- 认证与入口校验 ----------

    @Test
    void anonymousIs401() throws Exception {
        mvc.perform(post(HISTORY_URI).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"it-conv-his-anon\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
    }

    @Test
    void viewerIsAllowedOnOwnHistory() throws Exception {
        seedRow("it-conv-his-viewer-ok", "viewer", "user", "hi", null, null, null);
        String token = loginToken("viewer", "viewer123");
        mvc.perform(post(HISTORY_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"it-conv-his-viewer-ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void missingBodyIs400() throws Exception {
        String token = loginToken("admin", "admin123");
        mvc.perform(post(HISTORY_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体不能为空"));
    }

    @Test
    void blankAndOversizedConversationIdAre400() throws Exception {
        String token = loginToken("admin", "admin123");
        callHistory(token, "{\"conversation_id\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("conversation_id 不能为空"));
        callHistory(token, "{\"conversation_id\":\"" + "x".repeat(81) + "\"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("conversation_id 不能超过 80 字"));
    }

    @Test
    void invalidPageAndSizeAre400() throws Exception {
        String token = loginToken("admin", "admin123");
        callHistory(token, "{\"conversation_id\":\"it-conv-his-param\",\"page\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("page 必须大于等于 1"));
        callHistory(token, "{\"conversation_id\":\"it-conv-his-param\",\"size\":0}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size 必须在 1~100 之间"));
        callHistory(token, "{\"conversation_id\":\"it-conv-his-param\",\"size\":101}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("size 必须在 1~100 之间"));
    }

    // ---------- 本人历史:字段 / 时间正序 / 分页 ----------

    @Test
    void ownHistoryIsChronologicalWithFieldsAndNoOperatorLeak() throws Exception {
        seedRow("it-conv-his-fields", "viewer", "user", "AUD-001 库存还有多少？", null,
                "req-his-1", null);
        seedRow("it-conv-his-fields", "viewer", "assistant", "库存 22 件。",
                "[{\"tool\":\"query-stock-level\",\"ok\":true}]", "req-his-1", "success");
        seedRow("it-conv-his-fields", "viewer", "assistant", "服务暂时不可用", null,
                "req-his-2", "failed");

        String token = loginToken("viewer", "viewer123");
        MvcResult result = callHistory(token, "{\"conversation_id\":\"it-conv-his-fields\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conversationId").value("it-conv-his-fields"))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andReturn();

        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("items");
        assertThat(items).hasSize(3);
        assertThat(items.get(0).path("role").asText()).isEqualTo("user");
        assertThat(items.get(0).path("content").asText()).isEqualTo("AUD-001 库存还有多少？");
        assertThat(items.get(0).path("requestId").asText()).isEqualTo("req-his-1");
        assertThat(items.get(0).path("status").isNull()).isTrue();
        assertThat(items.get(0).path("toolCalls").isNull()).isTrue();
        assertThat(items.get(1).path("role").asText()).isEqualTo("assistant");
        assertThat(items.get(1).path("status").asText()).isEqualTo("success");
        assertThat(items.get(1).path("toolCalls").get(0).path("tool").asText())
                .isEqualTo("query-stock-level");
        assertThat(items.get(1).path("createdAt").asText()).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
        assertThat(items.get(2).path("status").asText()).isEqualTo("failed");
        // 归属列不回给前端(字段最小化)
        assertThat(items.get(0).has("operator")).isFalse();

        verify(agentChatClient, never()).chat(anyString(), anyString(), anyString(), anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void paginationSplitsPagesNewestFirstInChronologicalItems() throws Exception {
        for (int i = 1; i <= 5; i++) {
            seedRow("it-conv-his-page", "admin", "user", "第" + i + "条", null, "req-page-" + i, null);
        }
        String token = loginToken("admin", "admin123");

        MvcResult page1 = callHistory(token,
                "{\"conversation_id\":\"it-conv-his-page\",\"page\":1,\"size\":2}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(2))
                .andReturn();
        JsonNode items1 = objectMapper.readTree(page1.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("items");
        assertThat(items1).hasSize(2);
        assertThat(items1.get(0).path("content").asText()).isEqualTo("第4条");
        assertThat(items1.get(1).path("content").asText()).isEqualTo("第5条");

        MvcResult page3 = callHistory(token,
                "{\"conversation_id\":\"it-conv-his-page\",\"page\":3,\"size\":2}")
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items3 = objectMapper.readTree(page3.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("items");
        assertThat(items3).hasSize(1);
        assertThat(items3.get(0).path("content").asText()).isEqualTo("第1条");
    }

    // ---------- 归属:跨用户 403 不泄内容 / NULL 旧行不读出也不阻塞 / 未知会话空列表 ----------

    @Test
    void crossUserHistoryIs403WithoutContentLeak() throws Exception {
        seedRow("it-conv-his-cross", "admin", "user", "admin 的机密内容", null, "req-x", null);
        String token = loginToken("viewer", "viewer123");
        MvcResult result = callHistory(token, "{\"conversation_id\":\"it-conv-his-cross\"}")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权访问该会话"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("admin 的机密内容");
    }

    @Test
    void legacyNullRowsAreNotReturnedAndDoNotBlockOwnRows() throws Exception {
        seedRow("it-conv-his-mixed", null, "user", "旧数据 A", null, null, null);
        seedRow("it-conv-his-mixed", "viewer", "user", "viewer 的消息", null, "req-mix-1", null);
        seedRow("it-conv-his-mixed", null, "assistant", "旧数据 B", null, null, "success");

        String token = loginToken("viewer", "viewer123");
        MvcResult result = callHistory(token, "{\"conversation_id\":\"it-conv-his-mixed\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andReturn();
        JsonNode items = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("items");
        assertThat(items).hasSize(1);
        assertThat(items.get(0).path("content").asText()).isEqualTo("viewer 的消息");
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8)).doesNotContain("旧数据");
    }

    @Test
    void unknownConversationIsEmptyNotError() throws Exception {
        String token = loginToken("admin", "admin123");
        callHistory(token, "{\"conversation_id\":\"it-conv-his-none\"}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items").isEmpty());
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.ResultActions callHistory(String token, String body)
            throws Exception {
        return mvc.perform(post(HISTORY_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private void seedRow(String sessionId, String operator, String role, String content,
                         String toolCallsJson, String requestId, String status) {
        jdbc.update("INSERT INTO agent_conversation (session_id, operator, role, content, tool_calls_json, "
                        + "request_id, status, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')",
                sessionId, operator, role, content, toolCallsJson, requestId, status);
    }

    private String loginToken(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("data");
        return data.get("token").asText();
    }
}
