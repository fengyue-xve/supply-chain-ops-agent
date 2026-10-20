package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ChatHistoryMessage;
import com.demo.supplyagent.gateway.AgentChatClient;
import com.demo.supplyagent.gateway.UpstreamEnvelope;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3-② 会话落库集成测试(真实 SecurityFilterChain + 真实 ChatService/AgentConversationService + 真实 test 库,
 * AgentChatClient 以 mock 顶替)。覆盖:新会话自生成/指定复用/双行落库/request_id 关联/工具失败状态/
 * 上游失败与 400 透传仍留痕/超长 conversation_id 400 拦截/中文内容往返/库中无敏感信息。
 * 本类产生的行由 @AfterEach 清理(conv-% 与 it-conv-%),不影响 SchemaSeedSmokeTest 的种子计数断言。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatPersistenceIntegrationTest {

    private static final String CHAT_URI = "/api/chat";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String RID_HEADER = "X-Request-Id";

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
        jdbc.update("DELETE FROM agent_conversation WHERE session_id LIKE 'conv-%' OR session_id LIKE 'it-conv-%'");
    }

    // ---------- 新会话自动生成 + 双行落库 + 中文往返 + request_id ----------

    @Test
    void newConversationIsGeneratedAndBothRowsPersisted() throws Exception {
        String token = loginToken("admin", "admin123");
        String reply = "截至 2026-09-02 快照，AUD-001 库存 22 件。";
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream(reply,
                        "[{\"tool_call_id\":\"tc-1\",\"tool\":\"query-stock-level\","
                                + "\"params\":{\"skus\":[\"AUD-001\"]},\"ok\":true,\"round\":1,\"summary\":\"库存 22 件\"}]"));

        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(RID_HEADER, "req-persist-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"AUD-001 现在还有多少库存？\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(header().string(RID_HEADER, "req-persist-001"));

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(eq("Bearer " + token), eq("req-persist-001"), conv.capture(),
                eq("AUD-001 现在还有多少库存？"), any());
        String conversationId = conv.getValue();
        assertThat(conversationId).matches("conv-[0-9a-f]{12}");

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content, status, request_id, tool_calls_json FROM agent_conversation "
                        + "WHERE session_id = ? ORDER BY id", conversationId);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("role", "user")
                .containsEntry("content", "AUD-001 现在还有多少库存？")
                .containsEntry("status", null)
                .containsEntry("request_id", "req-persist-001");
        assertThat(rows.get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", reply)
                .containsEntry("status", "success")
                .containsEntry("request_id", "req-persist-001");
        assertThat((String) rows.get(1).get("tool_calls_json"))
                .contains("\"ok\":true").contains("query-stock-level");
    }

    // ---------- 指定 conversation_id 复用(连续两轮同会话) ----------

    @Test
    void providedConversationIdIsReusedAcrossTurns() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream("第一轮回复", null));

        callChat(token, "it-conv-reuse-001", "第一轮").andExpect(status().isOk());
        callChat(token, "it-conv-reuse-001", "第二轮").andExpect(status().isOk());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE session_id = 'it-conv-reuse-001'",
                Integer.class);
        assertThat(count).isEqualTo(4);
        verify(agentChatClient, times(2))
                .chat(anyString(), anyString(), eq("it-conv-reuse-001"), anyString(), any());
    }

    // ---------- 空白 conversation_id 视为缺失(生成) ----------

    @Test
    void blankConversationIdIsTreatedAsMissing() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream("hi", null));

        String body = objectMapper.writeValueAsString(Map.of("conversation_id", "   ", "message", "hi"));
        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(anyString(), anyString(), conv.capture(), eq("hi"), any());
        assertThat(conv.getValue()).matches("conv-[0-9a-f]{12}");
    }

    // ---------- 上游失败:错误语义不变 + failed 行留痕 + 库中无敏感信息 ----------

    @Test
    void upstreamFailurePersistsFailedTurnAndKeeps502Semantics() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new ApiException(502, AgentChatClient.UPSTREAM_UNAVAILABLE));

        MvcResult result = mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(RID_HEADER, "req-persist-502")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"AUD-001 库存怎么样？\"}"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(AgentChatClient.UPSTREAM_UNAVAILABLE))
                .andReturn();
        assertThat(result.getResponse().getHeader(RID_HEADER)).isEqualTo("req-persist-502");

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(eq("Bearer " + token), eq("req-persist-502"), conv.capture(),
                eq("AUD-001 库存怎么样？"), any());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content, status, request_id FROM agent_conversation WHERE session_id = ? ORDER BY id",
                conv.getValue());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0))
                .containsEntry("role", "user")
                .containsEntry("content", "AUD-001 库存怎么样？")
                .containsEntry("status", null)
                .containsEntry("request_id", "req-persist-502");
        assertThat(rows.get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", AgentChatClient.UPSTREAM_UNAVAILABLE)
                .containsEntry("status", "failed")
                .containsEntry("request_id", "req-persist-502");
        // 落库列绝不出现 Authorization/JWT 片段
        String dump = rows.toString();
        assertThat(dump).doesNotContain("Bearer").doesNotContain(token).doesNotContain("eyJ");
    }

    // ---------- 上游 400 信封原样透传,同样落 failed 行 ----------

    @Test
    void upstream400EnvelopePassesThroughAndPersistsFailedTurn() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(new UpstreamEnvelope(400, objectMapper.readTree(
                        "{\"code\":400,\"message\":\"请求体格式错误\",\"data\":null}")));

        MvcResult result = mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"))
                .andReturn();

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(anyString(), anyString(), conv.capture(), eq("hi"), any());
        // 上游 400 透传路径同样保留服务端生成会话 id 的响应头(失败路径可恢复)
        assertThat(result.getResponse().getHeader(ChatController.CONVERSATION_ID_HEADER))
                .isEqualTo(conv.getValue());
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content, status FROM agent_conversation WHERE session_id = ? ORDER BY id",
                conv.getValue());
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1))
                .containsEntry("role", "assistant")
                .containsEntry("content", "请求体格式错误")
                .containsEntry("status", "failed");
    }

    // ---------- 工具调用失败:本轮仍产出了回复 → status=success,失败明细在 tool_calls_json ----------

    @Test
    void toolCallFailureIsRecordedInToolCallsWithSuccessTurnStatus() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream("库存查询未成功。",
                        "[{\"tool_call_id\":\"tc-9\",\"tool\":\"foo\","
                                + "\"params\":{},\"ok\":false,\"round\":1,\"error\":\"未知工具：foo\"}]"));

        callChat(token, "it-conv-toolfail-001", "查个不存在的工具").andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, status, tool_calls_json FROM agent_conversation "
                        + "WHERE session_id = 'it-conv-toolfail-001' ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1)).containsEntry("role", "assistant").containsEntry("status", "success");
        assertThat((String) rows.get(1).get("tool_calls_json"))
                .contains("\"ok\":false").contains("未知工具：foo");
    }

    // ---------- 超长 conversation_id:400 拦截,不落库、不触达上游 ----------

    @Test
    void oversizedConversationIdIsRejectedWithoutPersistenceOrUpstream() throws Exception {
        String token = loginToken("admin", "admin123");
        String tooLong = "x".repeat(81);
        String body = objectMapper.writeValueAsString(Map.of("conversation_id", tooLong, "message", "hi"));

        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("conversation_id 不能超过 80 字"));
        verify(agentChatClient, never()).chat(anyString(), anyString(), anyString(), anyString(), any());
        Integer leftover = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE session_id = ?", Integer.class, tooLong);
        assertThat(leftover).isZero();
    }

    // ---------- W4-1:operator 归属落库 ----------

    @Test
    void operatorIsRecordedOnBothRows() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream("hi", null));

        callChat(token, "it-conv-owner-001", "hi").andExpect(status().isOk());

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, operator FROM agent_conversation WHERE session_id = 'it-conv-owner-001' ORDER BY id");
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(row -> assertThat(row).containsEntry("operator", "admin"));
    }

    // ---------- W4-1:跨用户会话 403,先于落库与上游 ----------

    @Test
    void crossUserConversationIs403WithoutRowsOrUpstreamCall() throws Exception {
        seedRow("it-conv-cross-001", "admin", "user", "admin 的会话", null, "req-seed", null);

        String token = loginToken("viewer", "viewer123");
        callChat(token, "it-conv-cross-001", "偷看")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value("无权访问该会话"))
                .andExpect(jsonPath("$.data").doesNotExist())
                // 403 语义不变:不为调用方携带的会话 id 回写响应头,响应不新增任何信息
                .andExpect(header().doesNotExist(ChatController.CONVERSATION_ID_HEADER));
        verify(agentChatClient, never()).chat(anyString(), anyString(), anyString(), anyString(), any());

        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_conversation WHERE session_id = 'it-conv-cross-001'", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    // ---------- W4-1:NULL 旧行不可见、不被回放、也不阻塞同 id 继续 ----------

    @Test
    void legacyNullOperatorRowsAreInvisibleButNotBlocking() throws Exception {
        seedRow("it-conv-legacy-001", null, "user", "W4-1 前的旧消息", null, "req-old", null);

        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream("hi", null));
        callChat(token, "it-conv-legacy-001", "继续问").andExpect(status().isOk());

        // 旧 NULL 行不进本轮历史(空历史)
        ArgumentCaptor<List<ChatHistoryMessage>> history = historyCaptor();
        verify(agentChatClient).chat(anyString(), anyString(), eq("it-conv-legacy-001"), eq("继续问"),
                history.capture());
        assertThat(history.getValue()).isEmpty();

        // 新行归属 viewer,旧行 operator 保持 NULL
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, operator, content FROM agent_conversation "
                        + "WHERE session_id = 'it-conv-legacy-001' ORDER BY id");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0)).containsEntry("operator", null).containsEntry("content", "W4-1 前的旧消息");
        assertThat(rows.get(1)).containsEntry("operator", "viewer").containsEntry("content", "继续问");
        assertThat(rows.get(2)).containsEntry("operator", "viewer").containsEntry("role", "assistant");
    }

    // ---------- W4-1:第二轮把本人历史(时间正序)传给上游 ----------

    @Test
    void secondTurnForwardsOwnHistoryToGatewayChronologically() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(okUpstream("第一轮回复", null));
        callChat(token, "it-conv-hist-001", "第一轮").andExpect(status().isOk());

        ArgumentCaptor<List<ChatHistoryMessage>> firstTurn = historyCaptor();
        verify(agentChatClient).chat(anyString(), anyString(), eq("it-conv-hist-001"), eq("第一轮"),
                firstTurn.capture());
        assertThat(firstTurn.getValue()).isEmpty();

        doReturn(okUpstream("第二轮回复", null))
                .when(agentChatClient).chat(anyString(), anyString(), anyString(), anyString(), any());
        callChat(token, "it-conv-hist-001", "第二轮").andExpect(status().isOk());

        ArgumentCaptor<List<ChatHistoryMessage>> secondTurn = historyCaptor();
        verify(agentChatClient).chat(anyString(), anyString(), eq("it-conv-hist-001"), eq("第二轮"),
                secondTurn.capture());
        assertThat(secondTurn.getValue()).extracting(ChatHistoryMessage::role)
                .containsExactly("user", "assistant");
        assertThat(secondTurn.getValue()).extracting(ChatHistoryMessage::content)
                .containsExactly("第一轮", "第一轮回复");
    }

    // ---------- W4-1:failed 助手行不回放(错误文案不能进入模型上下文) ----------

    @Test
    void failedAssistantTurnIsNotReplayed() throws Exception {
        String token = loginToken("viewer", "viewer123");
        // 同一测试内二次打桩必须用 doThrow/doReturn:when(mock.method()) 会真实调用 mock,
        // 命中已有 any() 桩会当场抛出/返回旧值
        doThrow(new ApiException(502, AgentChatClient.UPSTREAM_UNAVAILABLE))
                .when(agentChatClient).chat(anyString(), anyString(), anyString(), anyString(), any());
        callChat(token, "it-conv-hist-002", "第一轮").andExpect(status().isBadGateway());

        doReturn(okUpstream("ok", null))
                .when(agentChatClient).chat(anyString(), anyString(), anyString(), anyString(), any());
        callChat(token, "it-conv-hist-002", "第二轮").andExpect(status().isOk());

        ArgumentCaptor<List<ChatHistoryMessage>> history = historyCaptor();
        verify(agentChatClient).chat(anyString(), anyString(), eq("it-conv-hist-002"), eq("第二轮"),
                history.capture());
        assertThat(history.getValue()).extracting(ChatHistoryMessage::role).containsExactly("user");
        assertThat(history.getValue()).extracting(ChatHistoryMessage::content).containsExactly("第一轮");
    }

    // ---------- helpers ----------

    private void seedRow(String sessionId, String operator, String role, String content,
                         String toolCallsJson, String requestId, String status) {
        jdbc.update("DELETE FROM agent_conversation WHERE session_id = ?", sessionId);
        jdbc.update("INSERT INTO agent_conversation (session_id, operator, role, content, tool_calls_json, "
                        + "request_id, status, created_at, updated_at) "
                        + "VALUES (?,?,?,?,?,?,?, '2026-01-01 00:00:00.000', '2026-01-01 00:00:00.000')",
                sessionId, operator, role, content, toolCallsJson, requestId, status);
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ChatHistoryMessage>> historyCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private org.springframework.test.web.servlet.ResultActions callChat(String token, String conversationId,
                                                                         String message) throws Exception {
        String body = objectMapper.writeValueAsString(
                conversationId == null
                        ? Map.of("message", message)
                        : Map.of("conversation_id", conversationId, "message", message));
        return mvc.perform(post(CHAT_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String loginToken(String username, String password) throws Exception {
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

    private UpstreamEnvelope okUpstream(String reply, String toolCallsJson) throws Exception {
        String toolCalls = toolCallsJson == null ? "[]" : toolCallsJson;
        String json = "{\"code\":0,\"message\":\"ok\",\"data\":{\"request_id\":\"req-x\","
                + "\"conversation_id\":\"conv-echo\",\"reply\":" + objectMapper.writeValueAsString(reply)
                + ",\"tool_calls\":" + toolCalls + "}}";
        return new UpstreamEnvelope(200, objectMapper.readTree(json));
    }
}
