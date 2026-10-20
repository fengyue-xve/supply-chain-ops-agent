package com.demo.supplyagent.gateway;

import com.demo.supplyagent.dto.ChatHistoryMessage;
import com.demo.supplyagent.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Java → FastAPI POST /agent/chat 的协议代理客户端(W2-D3):
 * Authorization/X-Request-Id 原样透传,body 含 conversation_id/message 与可选 history(Java 裁剪后的
 * 历史文本消息,W4-1;空历史时完全省略该键,旧链路 payload 逐字节不变),
 * 不解析、不改造 Agent 的 tool_calls 等业务结构,错误统一抛 ApiException(信封与 HTTP 同号)。
 * 日志零敏感:不打印 Authorization/body,只打 requestId 与分类。
 */
@Component
public class AgentChatClient {

    public static final String UPSTREAM_UNAVAILABLE = "上游服务暂不可用，请稍后重试";
    public static final String UPSTREAM_TIMEOUT = "上游响应超时，请稍后重试";
    public static final String UPSTREAM_AUTH_FAILED = "上游服务鉴权状态异常，请稍后重试";
    private static final String UPSTREAM_BAD_BODY = "上游服务返回异常响应";
    private static final String UPSTREAM_BAD_REQUEST = "上游请求参数错误";

    private static final Logger log = LoggerFactory.getLogger(AgentChatClient.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient.Builder builder;
    private final String baseUrl;
    private final int timeoutSeconds;
    private volatile RestClient restClient;

    public AgentChatClient(RestClient.Builder builder,
                           @Value("${app.agent-gateway.base-url}") String baseUrl,
                           @Value("${app.agent-gateway.timeout-seconds:30}") int timeoutSeconds) {
        this.builder = builder;
        this.baseUrl = baseUrl;
        this.timeoutSeconds = timeoutSeconds;
    }

    /** 懒构建 RestClient:单测先 MockRestServiceServer.bindTo(builder) 再调用,首建即用 mock 工厂。 */
    private RestClient restClient() {
        RestClient local = restClient;
        if (local == null) {
            synchronized (this) {
                local = restClient;
                if (local == null) {
                    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout(timeoutSeconds * 1000);
                    factory.setReadTimeout(timeoutSeconds * 1000);
                    local = builder.clone().baseUrl(baseUrl).requestFactory(factory).build();
                    restClient = local;
                }
            }
        }
        return local;
    }

    public UpstreamEnvelope chat(String authorization, String requestId,
                                 String conversationId, String message,
                                 List<ChatHistoryMessage> history) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversation_id", conversationId);
        payload.put("message", message);
        if (history != null && !history.isEmpty()) {
            // 空历史不发送该键:无历史请求的 payload 与 W4-1 前逐字节一致
            payload.put("history", history);
        }
        try {
            return restClient().post().uri("/agent/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(headers -> {
                        headers.set(HttpHeaders.AUTHORIZATION, authorization);
                        headers.set("X-Request-Id", requestId);
                    })
                    .body(payload)
                    .exchange((request, response) -> {
                        int status = response.getStatusCode().value();
                        // 错误状态绝不读 body:HttpURLConnection 对 401 读 body 会抛
                        // HttpRetryException("cannot retry due to server authentication"),
                        // 且 401/403/5xx 本就只抛固定消息,不需要信封内容。
                        if (status == 401 || status == 403) {
                            // 归为上游异常 502:不能映射 401/403(Java 已验过用户 token 才走到这里,
                            // 误报会让前端清掉合法用户 token 回登录页);也不能映射 500(那是 Java
                            // 内部异常语义,这里错在上游响应,不是 Java 自身缺陷)。
                            throw new ApiException(502, UPSTREAM_AUTH_FAILED);
                        }
                        if (status == 400) {
                            JsonNode envelope = parseEnvelope(response.bodyTo(byte[].class));
                            if (envelope == null) {
                                throw new ApiException(400, UPSTREAM_BAD_REQUEST);
                            }
                            return new UpstreamEnvelope(status, envelope);
                        }
                        if (status < 200 || status >= 300) {
                            throw new ApiException(502, UPSTREAM_UNAVAILABLE);
                        }
                        // 2xx 信封原样透传;body 缺失/非 JSON = 上游异常
                        JsonNode envelope = parseEnvelope(response.bodyTo(byte[].class));
                        if (envelope == null) {
                            throw new ApiException(502, UPSTREAM_BAD_BODY);
                        }
                        return new UpstreamEnvelope(status, envelope);
                    });
        } catch (ResourceAccessException ex) {
            log.warn("agent-gateway 传输失败, requestId={}, 原因={}", requestId, ex.getMessage());
            throw mapTransportError(ex);
        }
    }

    /** 按 UTF-8 解析信封;byte[] 通道不受 Content-Type 影响,显式 UTF-8 防乱码。 */
    private static JsonNode parseEnvelope(byte[] raw) {
        if (raw == null || raw.length == 0) {
            return null;
        }
        try {
            return JSON.readTree(new String(raw, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            return null;
        }
    }

    private static ApiException mapTransportError(ResourceAccessException ex) {
        // JDK 连接与读取超时都抛 SocketTimeoutException,无法按类型细分:
        // 连接拒绝/主机不可达 → 502;任何超时(连接或读取) → 504。
        Throwable cause = ex;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        if (cause instanceof ConnectException || cause instanceof UnknownHostException) {
            return new ApiException(502, UPSTREAM_UNAVAILABLE);
        }
        if (cause instanceof SocketTimeoutException) {
            return new ApiException(504, UPSTREAM_TIMEOUT);
        }
        return new ApiException(502, UPSTREAM_UNAVAILABLE);
    }
}
