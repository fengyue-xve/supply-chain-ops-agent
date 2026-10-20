package com.demo.supplyagent.service;

import com.demo.supplyagent.dto.AuditHistoryData;
import com.demo.supplyagent.dto.AuditHistoryItem;
import com.demo.supplyagent.dto.AuditHistoryRequest;
import com.demo.supplyagent.mapper.AgentHistoryMapper;
import com.demo.supplyagent.web.ApiException;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * W3-④ 审计时间线查询（只读）。
 * 归一化：page 缺省 1（>=1）、size 缺省 20（1~100）；五个过滤串 trim 后空白视为未传，
 * 长度超过数据库列宽直接 400 而非截断；actionType 不做固定枚举白名单（未知值返回空结果）。
 * OFFSET 用 long 计算（防 int 溢出）；SQL 条件全部参数绑定，禁止拼接用户输入。
 */
@Service
public class AuditHistoryService {

    private static final int DEFAULT_PAGE = 1;
    private static final int DEFAULT_SIZE = 20;
    private static final int MIN_SIZE = 1;
    private static final int MAX_SIZE = 100;

    private final AgentHistoryMapper agentHistoryMapper;

    public AuditHistoryService(AgentHistoryMapper agentHistoryMapper) {
        this.agentHistoryMapper = agentHistoryMapper;
    }

    public AuditHistoryData query(AuditHistoryRequest request) {
        int page = normalizePage(request.getPage());
        int size = normalizeSize(request.getSize());
        String actionType = normalizeFilter(request.getActionType(), "actionType", 40);
        String taskKey = normalizeFilter(request.getTaskKey(), "taskKey", 80);
        String operator = normalizeFilter(request.getOperator(), "operator", 64);
        String requestId = normalizeFilter(request.getRequestId(), "requestId", 64);
        String conversationId = normalizeFilter(request.getConversationId(), "conversationId", 80);

        long offset = (long) (page - 1) * size;
        long total = agentHistoryMapper.countHistory(actionType, taskKey, operator, requestId, conversationId);
        List<AuditHistoryItem> items = agentHistoryMapper.selectHistoryPage(
                actionType, taskKey, operator, requestId, conversationId, size, offset);
        return new AuditHistoryData(items, total, page, size);
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

    /** trim 后空白 → null（等同未传）；超过列宽 → 400。 */
    private static String normalizeFilter(String value, String field, int maxLength) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > maxLength) {
            throw new ApiException(400, field + " 不能超过 " + maxLength + " 字符");
        }
        return trimmed;
    }
}
