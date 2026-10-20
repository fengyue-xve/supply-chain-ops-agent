package com.demo.supplyagent.web;

import com.demo.supplyagent.gateway.AgentChatClient;
import com.demo.supplyagent.security.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3-②:会话落库先于上游调用——DB 不可达时 /api/chat 返回统一 500 信封(持久化失败绝不伪装成对话成功),
 * 且绝不触达上游(不先烧一次模型调用)。
 * W4-1 起 ChatController 需从 principal 取 AuthUser(会话归属),@WithMockUser 的 Spring User 会被强转失败,
 * 故改为用 test profile secret 自签有效 JWT(真实登录需查库,本类库故意不可达)。
 * 独立上下文:url 指向未监听端口 3399 + 短连接超时,任何请求都不该碰真实 test 库(同 QueryStockLevelDbFailureTest)。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3399/supply_agent_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.hikari.connection-timeout=1500"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ChatDbFailureTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JwtProperties jwtProperties;

    @MockBean
    private AgentChatClient agentChatClient;

    @Test
    void dbDownReturnsUnified500EnvelopeAndNeverCallsUpstream() throws Exception {
        mvc.perform(post("/api/chat")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + buildToken("admin", "admin"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("数据库访问异常，请稍后重试"))
                .andExpect(jsonPath("$.data").value(nullValue()));
        verify(agentChatClient, never()).chat(anyString(), anyString(), anyString(), anyString(), any());
    }

    private String buildToken(String username, String role) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(username)
                .claim("role", role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plusSeconds(3600)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }
}
