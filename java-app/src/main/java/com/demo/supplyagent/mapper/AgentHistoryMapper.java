package com.demo.supplyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.supplyagent.dto.AuditHistoryItem;
import com.demo.supplyagent.entity.AgentHistory;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 审计时间线：应用层只 INSERT（无 update/delete 调用路径），永不 DELETE。
 * W3-④ 增加 admin 只读分页查询；项目无分页插件，LIMIT/OFFSET 与 COUNT 手写（禁用 selectPage）。
 */
public interface AgentHistoryMapper extends BaseMapper<AgentHistory> {

    /**
     * page 与 count 共用同一段 WHERE 常量，保证「同一过滤条件下 count 与结果行完全一致」；
     * 全部条件 #{...} 参数绑定，无字符串拼接。
     */
    String FILTER_WHERE = """
            <where>
              <if test="actionType != null">AND action_type = #{actionType}</if>
              <if test="taskKey != null">AND task_key = #{taskKey}</if>
              <if test="operator != null">AND operator = #{operator}</if>
              <if test="requestId != null">AND request_id = #{requestId}</if>
              <if test="conversationId != null">AND conversation_id = #{conversationId}</if>
            </where>
            """;

    /**
     * 固定排序 executed_at DESC, id DESC；executed_at 在 SQL 内格式化，
     * 避免为时间展示引入新的 JSON 序列化口径。before/after/stats 保持原字符串不解析。
     */
    @Select("""
            <script>
            SELECT id,
                   DATE_FORMAT(executed_at, '%Y-%m-%d %H:%i:%s') AS executedAt,
                   action_type     AS actionType,
                   who,
                   operator,
                   task_key        AS taskKey,
                   before_json     AS beforeJson,
                   after_json      AS afterJson,
                   stats_json      AS statsJson,
                   request_id      AS requestId,
                   conversation_id AS conversationId,
                   tool_call_id    AS toolCallId
            FROM agent_history
            """ + FILTER_WHERE + """
            ORDER BY executed_at DESC, id DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<AuditHistoryItem> selectHistoryPage(@Param("actionType") String actionType,
                                             @Param("taskKey") String taskKey,
                                             @Param("operator") String operator,
                                             @Param("requestId") String requestId,
                                             @Param("conversationId") String conversationId,
                                             @Param("limit") int limit,
                                             @Param("offset") long offset);

    @Select("""
            <script>
            SELECT COUNT(*)
            FROM agent_history
            """ + FILTER_WHERE + """
            </script>
            """)
    long countHistory(@Param("actionType") String actionType,
                      @Param("taskKey") String taskKey,
                      @Param("operator") String operator,
                      @Param("requestId") String requestId,
                      @Param("conversationId") String conversationId);
}
