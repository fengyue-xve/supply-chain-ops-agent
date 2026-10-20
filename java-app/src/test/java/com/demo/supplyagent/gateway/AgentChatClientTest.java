package com.demo.supplyagent.gateway;

import com.demo.supplyagent.dto.ChatHistoryMessage;
import com.demo.supplyagent.web.ApiException;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W2-D3 AgentChatClient 纯单测(JDK HttpServer 本机真实传输,无 Spring 上下文):
 * Authorization/X-Request-Id/conversation_id/message 原样转发;2xx 与 400 信封透传;
 * 上游 401/403 → Java 502(归上游异常,不误报用户会话也不占 500 内部异常语义);
 * 5xx → 502;连接拒绝 → 502;读超时 → 504;非 JSON 响应不泄堆栈。
 */
class AgentChatClientTest {

    private static final String AUTH = "Bearer unit-token-123";
    private static final String RID = "req-test-001";

    private final List<HttpServer> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(server -> server.stop(0));
    }

    /** 起本机 stub,返回 (client, 请求记录)。client 超时 1s(读超时用例需 < 服务延迟)。 */
    private AgentChatClient start(Responder responder) throws IOException {
        HttpServer http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/", exchange -> responder.handle(exchange));
        http.start();
        started.add(http);
        return new AgentChatClient(
                RestClient.builder(), "http://127.0.0.1:" + http.getAddress().getPort(), 1);
    }

    /** 记录请求并统一 200 信封响应(先落 holder 再写回,保证测试线程可读)。 */
    private static void recordAndOk(HttpExchange exchange, AtomicReference<Recorded> holder,
                                    String bodyJson) throws IOException {
        String method = exchange.getRequestMethod();
        String path = exchange.getRequestURI().getPath();
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        String requestId = exchange.getRequestHeaders().getFirst("X-Request-Id");
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        holder.set(new Recorded(method, path, authorization, requestId, body));
        byte[] out = bodyJson.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, out.length);
        exchange.getResponseBody().write(out);
        exchange.close();
    }

    @FunctionalInterface
    private interface Responder {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record Recorded(String method, String path, String authorization,
                            String requestId, String body) {
    }

    @Test
    void forwardsAuthorizationRequestIdAndBodyVerbatim() throws Exception {
        AtomicReference<Recorded> seen = new AtomicReference<>();
        AgentChatClient client = start(exchange ->
                recordAndOk(exchange, seen, "{\"code\":0,\"message\":\"ok\",\"data\":{}}"));

        UpstreamEnvelope result = client.chat(AUTH, RID, null, "AUD-001 库存还有多少？", null);

        Recorded r = seen.get();
        assertThat(r.method()).isEqualTo("POST");
        assertThat(r.path()).isEqualTo("/agent/chat");
        assertThat(r.authorization()).isEqualTo(AUTH);
        assertThat(r.requestId()).isEqualTo(RID);
        assertThat(r.body()).contains("\"message\":\"AUD-001 库存还有多少？\"");
        // W4-1:空历史不发送 history 键——无历史请求 payload 与旧链路逐字节一致
        assertThat(r.body()).doesNotContain("history");
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.envelope().path("code").asInt()).isEqualTo(0);
    }

    @Test
    void forwardsHistoryMessagesWhenPresent() throws Exception {
        AtomicReference<Recorded> seen = new AtomicReference<>();
        AgentChatClient client = start(exchange ->
                recordAndOk(exchange, seen, "{\"code\":0,\"message\":\"ok\",\"data\":{}}"));

        client.chat(AUTH, RID, "conv-known1234", "那补货呢？", List.of(
                new ChatHistoryMessage("user", "AUD-001 库存还有多少？"),
                new ChatHistoryMessage("assistant", "库存 22 件。")));

        String body = seen.get().body();
        assertThat(body).contains("\"conversation_id\":\"conv-known1234\"");
        assertThat(body).contains("\"role\":\"user\",\"content\":\"AUD-001 库存还有多少？\"");
        assertThat(body).contains("\"role\":\"assistant\",\"content\":\"库存 22 件。\"");
    }

    @Test
    void forwardsConversationIdWhenPresent() throws Exception {
        AtomicReference<Recorded> seen = new AtomicReference<>();
        AgentChatClient client = start(exchange ->
                recordAndOk(exchange, seen, "{\"code\":0,\"message\":\"ok\",\"data\":{}}"));

        client.chat(AUTH, RID, "conv-known1234", "近7天销量", null);

        Recorded r = seen.get();
        assertThat(r.body()).contains("\"conversation_id\":\"conv-known1234\"");
    }

    @Test
    void passesThroughFastApi400EnvelopeUnchanged() throws Exception {
        AgentChatClient client = start(exchange -> {
            byte[] out = "{\"code\":400,\"message\":\"请求体格式错误\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        UpstreamEnvelope result = client.chat(AUTH, RID, null, "x", null);

        assertThat(result.httpStatus()).isEqualTo(400);
        assertThat(result.envelope().path("message").asText()).isEqualTo("请求体格式错误");
    }

    @Test
    void upstream401And403AreSurfacedAs502NotUserAuthError() throws Exception {
        // Java 已验过用户 token 才可能到上游:上游 401/403 = 上游鉴权/透传异常,
        // 统一 502(上游异常语义);code=502 而非 401/403/500——前端只认 401 清 token,
        // 不会把合法用户会话误踢回登录页;500 则保留给 Java 内部缺陷语义。
        for (int status : new int[]{401, 403}) {
            int code = status;
            AgentChatClient client = start(exchange -> {
                byte[] out = ("{\"code\":" + code + ",\"message\":\"up\",\"data\":null}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(code, out.length);
                exchange.getResponseBody().write(out);
                exchange.close();
            });
            assertThatThrownBy(() -> client.chat(AUTH, RID, null, "x", null))
                    .isInstanceOf(ApiException.class)
                    .extracting(e -> ((ApiException) e).getCode())
                    .isEqualTo(502);
        }
    }

    @Test
    void upstream5xxIs502WithFixedMessage() throws Exception {
        AgentChatClient client = start(exchange -> {
            byte[] out = "{\"code\":500,\"message\":\"服务内部错误\",\"data\":null}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(500, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertThatThrownBy(() -> client.chat(AUTH, RID, null, "x", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(502);
    }

    @Test
    void connectionRefusedIs502() throws Exception {
        // 找一个当前无监听的空闲端口模拟上游宕机
        int freePort;
        try (ServerSocket socket = new ServerSocket(0)) {
            freePort = socket.getLocalPort();
        }
        AgentChatClient client = new AgentChatClient(
                RestClient.builder(), "http://127.0.0.1:" + freePort, 1);

        assertThatThrownBy(() -> client.chat(AUTH, RID, null, "x", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(502);
    }

    @Test
    void readTimeoutIs504() throws Exception {
        AgentChatClient client = start(exchange -> {
            try {
                Thread.sleep(2500);  // client 读超时 1s,必触发
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] out = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertThatThrownBy(() -> client.chat(AUTH, RID, null, "x", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(504);
    }

    @Test
    void nonJson2xxIs502BadBody() throws Exception {
        AgentChatClient client = start(exchange -> {
            byte[] out = "oops".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });

        assertThatThrownBy(() -> client.chat(AUTH, RID, null, "x", null))
                .isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).getCode())
                .isEqualTo(502);
    }
}
