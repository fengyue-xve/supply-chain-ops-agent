package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.ChatHistoryMessage;
import com.demo.supplyagent.gateway.AgentChatClient;
import com.demo.supplyagent.gateway.UpstreamEnvelope;
import com.demo.supplyagent.security.AuthUser;
import com.demo.supplyagent.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * 对话代理(W2-D3)+ 会话落库(W3-②)+ 历史回放与归属(W4-1):把已认证请求的
 * Authorization/request_id/conversation_id/message 透传给 FastAPI Gateway,并把「用户消息 + 助手结果」
 * 落库——Java 是数据库唯一持有者,FastAPI 不连库,历史由 Java 裁剪后随请求下发。
 * 落库失败不吞:直接冒泡 → 统一 500,绝不伪装成对话成功;上游错误码语义(502/504/400)不变。
 * 跨用户 conversation_id 在落库与触达上游之前 403(归属校验先于一切副作用)。
 * conversation_id 由入口(ChatController)解析:本层只消费已解析的 id,绝不另行生成——
 * 服务端生成的那一个会同步回写 X-Conversation-Id 响应头,两处必须一致。
 */
@Service
public class ChatService {

    private static final Logger log = LoggerFactory.getLogger(ChatService.class);
    private static final String CONVERSATION_PREFIX = "conv-";

    private final AgentChatClient agentChatClient;
    private final AgentConversationService conversationService;

    public ChatService(AgentChatClient agentChatClient, AgentConversationService conversationService) {
        this.agentChatClient = agentChatClient;
        this.conversationService = conversationService;
    }

    public ResponseEntity<JsonNode> chat(String authorization, String requestId, String conversationId,
                                         String message, AuthUser user) {
        long start = System.currentTimeMillis();
        // 归属校验先于一切副作用:跨用户会话直接 403,不落库、不触达上游、不烧模型调用
        conversationService.assertAccessible(conversationId, user.username());
        // 历史在落当前用户消息之前读取(本轮消息不能进入自己的上下文);空历史 = 旧链路
        List<ChatHistoryMessage> history = conversationService.loadForModel(conversationId, user.username());
        // 先落用户消息再调上游:库不可用时立即 500,不会先烧一次模型调用
        conversationService.appendUserMessage(conversationId, message, requestId, user.username());

        UpstreamEnvelope upstream;
        try {
            upstream = agentChatClient.chat(authorization, requestId, conversationId, message, history);
        } catch (ApiException ex) {
            // 本轮失败也留痕:助手行 status=failed,内容 = 返回给前端的公开文案(无堆栈/无敏感信息)
            conversationService.appendAssistantFailure(conversationId, ex.getMessage(), requestId, user.username());
            throw ex;
        }

        int status = upstream.httpStatus();
        if (status >= 200 && status < 300) {
            JsonNode data = upstream.envelope().path("data");
            JsonNode toolCalls = data.path("tool_calls");
            String toolCallsJson = toolCalls.isArray() && !toolCalls.isEmpty() ? toolCalls.toString() : null;
            conversationService.appendAssistantSuccess(conversationId, data.path("reply").asText(null),
                    toolCallsJson, requestId, user.username());
        } else {
            // 上游 400 信封透传:本轮无回复,同样落 failed 行
            conversationService.appendAssistantFailure(conversationId,
                    upstream.envelope().path("message").asText("本轮对话未完成"), requestId, user.username());
        }
        log.info("chat 代理完成, requestId={}, conversationId={}, httpStatus={}, 历史条数={}, 耗时={}ms",
                requestId, conversationId, status, history.size(), System.currentTimeMillis() - start);
        return ResponseEntity.status(status).body(upstream.envelope());
    }

    /** 服务端会话 id 生成(格式与 FastAPI ids.py 对齐:conv- + 12 hex);由入口调用并回写 X-Conversation-Id。 */
    public static String newConversationId() {
        return CONVERSATION_PREFIX + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
