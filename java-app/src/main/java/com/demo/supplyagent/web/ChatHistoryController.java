package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.dto.ChatHistoryData;
import com.demo.supplyagent.dto.ChatHistoryRequest;
import com.demo.supplyagent.service.AgentConversationService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * W4-1 会话历史查询（只读，本人可见）。
 * 权限：既非 admin-only 也非匿名——/api/chat/history 走 SecurityConfig anyRequest().authenticated()
 * （匿名 401）；「本人」由服务层按 operator 强制：会话含他人行 → 403，NULL 旧行不读出。
 * 前端刷新续会话时用它恢复气泡（conversation_id 由前端 sessionStorage 记住）。
 */
@RestController
public class ChatHistoryController {

    private final AgentConversationService conversationService;

    public ChatHistoryController(AgentConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @PostMapping("/api/chat/history")
    public ApiResponse<ChatHistoryData> history(@RequestBody(required = false) ChatHistoryRequest request) {
        if (request == null) {
            throw new ApiException(400, "请求体不能为空");
        }
        return ApiResponse.ok(conversationService.historyPage(request, WriteHeaders.authenticatedUser().username()));
    }
}
