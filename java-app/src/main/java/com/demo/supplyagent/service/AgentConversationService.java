package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.ChatHistoryData;
import com.demo.supplyagent.dto.ChatHistoryItem;
import com.demo.supplyagent.dto.ChatHistoryMessage;
import com.demo.supplyagent.dto.ChatHistoryRequest;
import com.demo.supplyagent.entity.AgentConversation;
import com.demo.supplyagent.mapper.AgentConversationMapper;
import com.demo.supplyagent.web.ApiException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话落库(W3-②)+ 会话归属与历史(W4-1):每次 /api/chat 写「用户消息 + 助手结果」两行,
 * 同 session_id / request_id 关联并记录 operator(JWT sub)归属。
 *
 * 归属口径:会话可访问 ⇔ 该 session 下不存在「他人」行;operator=NULL 的旧数据不属于任何用户,
 * 既不阻塞访问也不被读出/回放。历史读取一律按 (operator, session_id) 等值过滤。
 *
 * 数据库异常不吞不降级:直接冒泡给 ApiExceptionHandler → 统一 500 信封,绝不伪装成对话成功。
 * 只落业务内容,绝不落 Authorization/JWT/密钥。
 */
@Service
public class AgentConversationService {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    /** 模型上下文上限(W4-1):最近 N 条 / 总字符预算 / 单条上限(超出截断,只截尾部)。 */
    static final int MAX_REPLAY_MESSAGES = 12;
    static final int REPLAY_CHAR_BUDGET = 4000;
    static final int MAX_REPLAY_CONTENT_CHARS = 2000;
    static final String TRUNCATED_SUFFIX = "…（已截断）";

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;
    private static final DateTimeFormatter CREATED_AT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final AgentConversationMapper mapper;
    private final ObjectMapper objectMapper;

    public AgentConversationService(AgentConversationMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    // ---------- 落库 ----------

    public void appendUserMessage(String sessionId, String content, String requestId, String operator) {
        insert(sessionId, content, ROLE_USER, null, null, requestId, operator);
    }

    public void appendAssistantSuccess(String sessionId, String content, String toolCallsJson,
                                       String requestId, String operator) {
        insert(sessionId, content, ROLE_ASSISTANT, toolCallsJson, STATUS_SUCCESS, requestId, operator);
    }

    public void appendAssistantFailure(String sessionId, String content, String requestId, String operator) {
        insert(sessionId, content, ROLE_ASSISTANT, null, STATUS_FAILED, requestId, operator);
    }

    private void insert(String sessionId, String content, String role, String toolCallsJson,
                        String status, String requestId, String operator) {
        AgentConversation row = new AgentConversation();
        row.setSessionId(sessionId);
        row.setOperator(operator);
        row.setRole(role);
        row.setContent(content);
        row.setToolCallsJson(toolCallsJson);
        row.setRequestId(requestId);
        row.setStatus(status);
        LocalDateTime now = LocalDateTime.now();
        row.setCreatedAt(now);
        row.setUpdatedAt(now);
        mapper.insert(row);
    }

    // ---------- 归属 ----------

    /**
     * 跨用户访问会话 → 403(不落库、不触达上游);NULL operator 旧行不构成归属,
     * 既不对任何人开放历史,也不阻塞新用户在同一 id 上继续(旧行永不读出)。
     */
    public void assertAccessible(String sessionId, String operator) {
        for (String owner : mapper.selectOperators(sessionId)) {
            if (owner != null && !owner.equals(operator)) {
                throw new ApiException(403, "无权访问该会话");
            }
        }
    }

    // ---------- 历史(W4-1) ----------

    /**
     * 传给模型的历史消息(时间正序):只重放 user 文本行与 status=success 的助手文本行——
     * 不重放 failed 错误文案、不伪造 tool-call 协议消息。条数/总预算/单条长度三重上限,
     * 从最新往回取,超出预算的旧消息整条丢弃(不做跨消息拼接)。
     */
    public List<ChatHistoryMessage> loadForModel(String sessionId, String operator) {
        List<AgentConversation> newestFirst =
                mapper.selectReplayCandidates(operator, sessionId, MAX_REPLAY_MESSAGES);
        List<ChatHistoryMessage> newestFirstKept = new ArrayList<>();
        int budget = REPLAY_CHAR_BUDGET;
        for (AgentConversation row : newestFirst) {
            String content = row.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            if (content.length() > MAX_REPLAY_CONTENT_CHARS) {
                content = content.substring(0, MAX_REPLAY_CONTENT_CHARS) + TRUNCATED_SUFFIX;
            }
            if (content.length() > budget) {
                break;
            }
            budget -= content.length();
            newestFirstKept.add(new ChatHistoryMessage(row.getRole(), content));
        }
        Collections.reverse(newestFirstKept);
        return newestFirstKept;
    }

    /**
     * 会话历史查询(本人):page=1 为最近一页,items 反转成时间正序返回;
     * tool_calls_json 原样解析为数组(解析失败为 null,不因此 500)。
     */
    public ChatHistoryData historyPage(ChatHistoryRequest request, String operator) {
        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw new ApiException(400, "conversation_id 不能为空");
        }
        if (conversationId.length() > 80) {
            throw new ApiException(400, "conversation_id 不能超过 80 字");
        }
        int page = normalizePage(request.getPage());
        int size = normalizeSize(request.getSize());
        assertAccessible(conversationId, operator);

        long offset = (long) (page - 1) * size;
        long total = mapper.countHistory(operator, conversationId);
        List<AgentConversation> rows = mapper.selectHistoryPage(operator, conversationId, size, offset);
        List<ChatHistoryItem> items = new ArrayList<>(rows.size());
        for (AgentConversation row : rows) {
            items.add(new ChatHistoryItem(
                    row.getRole(),
                    row.getContent(),
                    row.getRequestId(),
                    row.getStatus(),
                    row.getCreatedAt() == null ? null : row.getCreatedAt().format(CREATED_AT),
                    parseJson(row.getToolCallsJson())));
        }
        Collections.reverse(items);
        return new ChatHistoryData(conversationId, items, total, page, size);
    }

    private JsonNode parseJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(raw);
        } catch (Exception e) {
            return null;
        }
    }

    private static int normalizePage(Integer page) {
        if (page == null) {
            return DEFAULT_PAGE;
        }
        if (page < DEFAULT_PAGE) {
            throw new ApiException(400, "page 必须大于等于 1");
        }
        return page;
    }

    private static int normalizeSize(Integer size) {
        if (size == null) {
            return DEFAULT_SIZE;
        }
        if (size < MIN_SIZE || size > MAX_SIZE) {
            throw new ApiException(400, "size 必须在 1~100 之间");
        }
        return size;
    }
}
