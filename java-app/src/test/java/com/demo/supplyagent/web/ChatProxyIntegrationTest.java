package com.demo.supplyagent.web;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.demo.supplyagent.gateway.AgentChatClient;
import com.demo.supplyagent.gateway.UpstreamEnvelope;
import com.demo.supplyagent.security.JwtProperties;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
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

import javax.crypto.SecretKey;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2-D3 /api/chat 全链集成测试(真实 SecurityFilterChain + 真实 ChatController/ChatService,
 * AgentChatClient 以 mock 顶替验证「Java 认证失败绝不触达上游」与「成功/超时信封透传」;
 * 传输层行为见 AgentChatClientTest)。真实 FastAPI 联调留冒烟。
 * W3-② 起本类走真实 test 库落库:缺省 conversation_id 由 Java 生成(conv- 前缀),
 * 产生的会话行由 @AfterEach 清理;落库语义细节见 ChatPersistenceIntegrationTest。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatProxyIntegrationTest {

    private static final String CHAT_URI = "/api/chat";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String RID_HEADER = "X-Request-Id";
    private static final String UPSTREAM_OK =
            "{\"code\":0,\"message\":\"ok\",\"data\":{\"request_id\":\"r\",\"conversation_id\":\"c\",\"reply\":\"hi\",\"tool_calls\":[]}}";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtProperties jwtProperties;

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

    // ---------- 认证失败:401 信封且绝不调用上游 ----------

    @Test
    void anonymousChatIs401AndNeverCallsGateway() throws Exception {
        mvc.perform(post(CHAT_URI).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.data").doesNotExist());
        verify(agentChatClient, never()).chat(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void forgedTokenChatIs401AndNeverCallsGateway() throws Exception {
        String token = loginToken("admin", "admin123");
        int secondDot = token.indexOf('.', token.indexOf('.') + 1);
        int idx = secondDot + 3;
        char c = token.charAt(idx);
        String forged = token.substring(0, idx) + (c == 'a' ? 'b' : 'a') + token.substring(idx + 1);
        callChat(forged, "hi")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verify(agentChatClient, never()).chat(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void expiredTokenChatIs401AndNeverCallsGateway() throws Exception {
        callChat(buildExpiredToken("admin", "admin"), "hi")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401));
        verify(agentChatClient, never()).chat(anyString(), anyString(), any(), anyString(), any());
    }

    // ---------- 双角色放行(权限判定在工具接口,chat 只要求认证) ----------

    @Test
    void adminChatReachesGatewayWithOriginalAuthorization() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));

        MvcResult result = callChat(token, "AUD-001 库存?")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("hi"))
                .andReturn();

        ArgumentCaptor<String> auth = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> rid = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> message = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(auth.capture(), rid.capture(), conv.capture(), message.capture(), any());
        assertThat(auth.getValue()).isEqualTo("Bearer " + token);
        assertThat(message.getValue()).isEqualTo("AUD-001 库存?");
        assertThat(conv.getValue()).matches("conv-[0-9a-f]{12}");
        assertThat(result.getResponse().getHeader(RID_HEADER)).isEqualTo(rid.getValue());
    }

    @Test
    void viewerChatIsAllowed() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));
        callChat(token, "近7天销量")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    // ---------- request_id 贯穿 ----------

    @Test
    void requestIdPresentIsForwardedVerbatim() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));

        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(RID_HEADER, "req-client-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(RID_HEADER, "req-client-001"));
        verify(agentChatClient).chat(eq("Bearer " + token), eq("req-client-001"), anyString(), eq("hi"), any());
    }

    @Test
    void requestIdMissingIsGeneratedAndCarried() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));

        MvcResult result = callChat(token, "hi").andExpect(status().isOk()).andReturn();
        String responseRid = result.getResponse().getHeader(RID_HEADER);
        assertThat(responseRid).isNotBlank();

        ArgumentCaptor<String> rid = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(eq("Bearer " + token), rid.capture(), anyString(), eq("hi"), any());
        assertThat(rid.getValue()).isEqualTo(responseRid);
    }

    // ---------- W4 收口:服务端生成会话 id 经 X-Conversation-Id 回写(成功与 502/504 都在) ----------

    @Test
    void serverGeneratedConversationIdIsEchoedInHeaderOnSuccess() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));

        MvcResult result = callChat(token, "hi").andExpect(status().isOk()).andReturn();

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(anyString(), anyString(), conv.capture(), anyString(), any());
        // 回写的头与真实透传给上游的会话 id 完全一致(不是两次独立生成)
        assertThat(result.getResponse().getHeader(ChatController.CONVERSATION_ID_HEADER))
                .isEqualTo(conv.getValue())
                .matches("conv-[0-9a-f]{12}");
    }

    @Test
    void serverGeneratedConversationIdHeaderSurvivesUpstream502() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new ApiException(502, AgentChatClient.UPSTREAM_UNAVAILABLE));

        MvcResult result = callChat(token, "hi")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andReturn();

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(anyString(), anyString(), conv.capture(), anyString(), any());
        assertThat(result.getResponse().getHeader(ChatController.CONVERSATION_ID_HEADER))
                .isEqualTo(conv.getValue());
    }

    @Test
    void serverGeneratedConversationIdHeaderSurvivesUpstream504() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenThrow(new ApiException(504, AgentChatClient.UPSTREAM_TIMEOUT));

        MvcResult result = callChat(token, "hi")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value(504))
                .andReturn();

        ArgumentCaptor<String> conv = ArgumentCaptor.forClass(String.class);
        verify(agentChatClient).chat(anyString(), anyString(), conv.capture(), anyString(), any());
        assertThat(result.getResponse().getHeader(ChatController.CONVERSATION_ID_HEADER))
                .isEqualTo(conv.getValue());
    }

    @Test
    void clientProvidedConversationIdIsForwardedVerbatimAndNoHeader() throws Exception {
        String token = loginToken("admin", "admin123");
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));

        MvcResult result = mvc.perform(post(CHAT_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"conversation_id\":\"it-conv-provided-1\",\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andReturn();

        // 调用方携带的 id 原样透传,绝不被替换;此时无恢复需求,不回写该头
        verify(agentChatClient).chat(anyString(), anyString(), eq("it-conv-provided-1"), eq("hi"), any());
        assertThat(result.getResponse().getHeader(ChatController.CONVERSATION_ID_HEADER)).isNull();
    }

    // ---------- 参数校验:400 且不触达上游 ----------

    @Test
    void blankOrOversizedMessageReturns400WithoutGatewayCall() throws Exception {
        String token = loginToken("admin", "admin123");
        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("message 不能为空"));
        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"" + "x".repeat(2001) + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        verify(agentChatClient, never()).chat(anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void oversizedRequestIdIs400AtBoundary64WithoutGatewayCall() throws Exception {
        String token = loginToken("admin", "admin123");
        // 65 字符:超过 agent_conversation.request_id 列宽 64,入口 400 而非落库 500
        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(RID_HEADER, "r".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("X-Request-Id 不能超过 64 字符"));
        verify(agentChatClient, never()).chat(anyString(), anyString(), any(), anyString(), any());

        // 恰 64 字符:边界合法,正常放行
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(upstream(200, UPSTREAM_OK));
        mvc.perform(post(CHAT_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header(RID_HEADER, "r".repeat(64))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(RID_HEADER, "r".repeat(64)));
    }

    // ---------- 上游错误:统一信封 + request_id 仍可定位 ----------

    @Test
    void upstreamAuthFailureSurfaces502EnvelopeSoFrontendKeepsToken() throws Exception {
        // 上游 401/403 → Java 502:HTTP 与 body.code 一致(502),code!=401/403 前端
        // 就不会清 token 回登录页;也不映射 500(那是 Java 内部异常语义)。
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new ApiException(502, AgentChatClient.UPSTREAM_AUTH_FAILED));

        MvcResult result = callChat(token, "hi")
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value(502))
                .andExpect(jsonPath("$.message").value(AgentChatClient.UPSTREAM_AUTH_FAILED))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getHeader(RID_HEADER)).isNotBlank();
    }

    @Test
    void gatewayTimeoutSurfaces504EnvelopeWithRequestId() throws Exception {
        String token = loginToken("viewer", "viewer123");
        when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new ApiException(504, AgentChatClient.UPSTREAM_TIMEOUT));

        MvcResult result = callChat(token, "hi")
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value(504))
                .andExpect(jsonPath("$.message").value(AgentChatClient.UPSTREAM_TIMEOUT))
                .andExpect(jsonPath("$.data").doesNotExist())
                .andReturn();
        assertThat(result.getResponse().getHeader(RID_HEADER)).isNotBlank();
    }

    // ---------- 日志不泄密 ----------

    @Test
    void chatProxyLogsNeverContainTokenOrPasswordOrSecret() throws Exception {
        Logger projectLogger = (Logger) LoggerFactory.getLogger("com.demo.supplyagent");
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        projectLogger.addAppender(appender);
        try {
            String token = loginToken("admin", "admin123");
            when(agentChatClient.chat(anyString(), anyString(), any(), anyString(), any()))
                    .thenThrow(new ApiException(502, AgentChatClient.UPSTREAM_UNAVAILABLE));
            callChat(token, "hi").andExpect(status().isBadGateway());
        } finally {
            projectLogger.detachAppender(appender);
        }
        String logs = appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (a, b) -> a + "\n" + b);
        assertThat(logs)
                .doesNotContain("admin123", "viewer123")
                .doesNotContain(jwtProperties.getSecret());
    }

    // ---------- helpers ----------

    private org.springframework.test.web.servlet.ResultActions callChat(String token, String message)
            throws Exception {
        var request = post(CHAT_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"message\":\"" + message + "\"}");
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return mvc.perform(request);
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

    private UpstreamEnvelope upstream(int status, String json) throws Exception {
        return new UpstreamEnvelope(status, objectMapper.readTree(json));
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
