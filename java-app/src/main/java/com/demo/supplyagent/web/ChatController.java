package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ChatRequest;
import com.demo.supplyagent.service.ChatService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 对话入口 POST /api/chat(W2-D3):走 SecurityConfig 统一认证(anyRequest authenticated,
 * 无 token/伪造/过期 → 401 信封,由 JwtAuthFilter/EntryPoint 处理,不会触达本层)。
 * 薄壳:参数校验 + request_id 生成/回写 + conversation_id 入口解析 + 交给 ChatService 代理
 * FastAPI 并落库(W3-②)。响应体 = FastAPI 上游信封原样透传(不包第二层信封);错误(上游不可用
 * 502/超时 504 等)由 AgentChatClient 抛 ApiException → ApiExceptionHandler 统一信封,
 * request_id 已回写响应头;服务端自生成的 conversation_id 同样早已回写 X-Conversation-Id。
 */
@RestController
public class ChatController {

    public static final String REQUEST_ID_HEADER = "X-Request-Id";
    public static final String CONVERSATION_ID_HEADER = "X-Conversation-Id";
    static final int MAX_MESSAGE_LENGTH = 2000;
    static final int MAX_CONVERSATION_ID_LENGTH = 80;
    /** agent_conversation.request_id 列宽 64：超长透传会在落库时报 500，入口先拦 400。 */
    static final int MAX_REQUEST_ID_LENGTH = 64;

    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @PostMapping("/api/chat")
    public ResponseEntity<JsonNode> chat(@RequestBody(required = false) ChatRequest request,
                                         HttpServletRequest httpRequest,
                                         HttpServletResponse response) {
        // 先落 request_id(有则透传、无则生成):成功与失败(400/502/504/兜底 500)
        // 都能用同一 id 定位,回写必须早于任何校验抛错
        String requestId = httpRequest.getHeader(REQUEST_ID_HEADER);
        // 超长(落库列宽 64)先拦 400:非法 id 不回写也不透传,不落到 DB 才报截断/500
        if (requestId != null && requestId.length() > MAX_REQUEST_ID_LENGTH) {
            throw new ApiException(400, "X-Request-Id 不能超过 " + MAX_REQUEST_ID_LENGTH + " 字符");
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(REQUEST_ID_HEADER, requestId);
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        if (request.getMessage() == null || request.getMessage().isBlank()) {
            throw new ApiException(400, "message 不能为空");
        }
        if (request.getMessage().length() > MAX_MESSAGE_LENGTH) {
            throw new ApiException(400, "message 不能超过 2000 字");
        }
        // W3-②:conversation_id 落库列宽 80,超长在此拦 400(不落到 DB 才报 500)
        if (request.getConversationId() != null
                && request.getConversationId().length() > MAX_CONVERSATION_ID_LENGTH) {
            throw new ApiException(400, "conversation_id 不能超过 80 字");
        }
        // 会话 id 在入口即解析(服务端生成时不进服务层再生成,避免两处生成不一致):
        // 服务端自生成的 id 通过响应头回传——成功与 502/504 等失败路径都保留,
        // 首轮失败后前端仍能凭该头恢复会话归属;调用方已传入 conversation_id 时
        // 不生成、不回写,绝不覆盖。回写早于服务调用,与 request_id 同策略。
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            conversationId = ChatService.newConversationId();
            response.setHeader(CONVERSATION_ID_HEADER, conversationId);
        }
        String authorization = httpRequest.getHeader(HttpHeaders.AUTHORIZATION);
        // anyRequest().authenticated() 之后主体必为 JwtAuthFilter 放入的 AuthUser(W4-1 会话归属)
        return chatService.chat(authorization, requestId, conversationId,
                request.getMessage(), WriteHeaders.authenticatedUser());
    }
}
