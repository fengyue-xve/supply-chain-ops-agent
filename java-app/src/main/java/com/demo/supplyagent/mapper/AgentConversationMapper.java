package com.demo.supplyagent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.demo.supplyagent.entity.AgentConversation;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 会话消息读写（W3-② 落库 / W4-1 归属）。
 * 历史读取一律按 (operator, session_id) 过滤——NULL operator 的旧数据不属于任何用户，永不读出；
 * 项目无分页插件，LIMIT/OFFSET 与 COUNT 手写（禁用 selectPage，与 AgentHistoryMapper 同约定）。
 */
public interface AgentConversationMapper extends BaseMapper<AgentConversation> {

    /**
     * 会话归属判定用：该 session 下出现过的全部 operator 值（DISTINCT，可能含 NULL）。
     * 应用层据此判定「会话含他人行 → 403」；只读、不修改归属。
     */
    @Select("SELECT DISTINCT operator FROM agent_conversation WHERE session_id = #{sessionId}")
    List<String> selectOperators(@Param("sessionId") String sessionId);

    /**
     * 最近一页历史（id DESC = 从最新往回取）；调用方负责反转成时间正序。
     * operator 等值绑定：NULL 旧行与跨用户行都不可能被读出。
     */
    @Select("""
            SELECT id, session_id AS sessionId, operator, role, content, tool_calls_json AS toolCallsJson,
                   request_id AS requestId, status, created_at AS createdAt, updated_at AS updatedAt
            FROM agent_conversation
            WHERE session_id = #{sessionId} AND operator = #{operator}
            ORDER BY id DESC
            LIMIT #{limit} OFFSET #{offset}
            """)
    List<AgentConversation> selectHistoryPage(@Param("operator") String operator,
                                              @Param("sessionId") String sessionId,
                                              @Param("limit") int limit,
                                              @Param("offset") long offset);

    /**
     * 模型上下文用的文本消息（只取 user/assistant 文本行）：
     * 排除 status=failed 的助手行（那是给用户看的错误文案，不是助手发言）；id DESC 取最近 N 条。
     */
    @Select("""
            <script>
            SELECT id, session_id AS sessionId, operator, role, content, request_id AS requestId, status,
                   created_at AS createdAt, updated_at AS updatedAt
            FROM agent_conversation
            WHERE session_id = #{sessionId} AND operator = #{operator}
              AND (role = 'user' OR (role = 'assistant' AND status = 'success'))
            ORDER BY id DESC
            LIMIT #{limit}
            </script>
            """)
    List<AgentConversation> selectReplayCandidates(@Param("operator") String operator,
                                                   @Param("sessionId") String sessionId,
                                                   @Param("limit") int limit);

    @Select("SELECT COUNT(*) FROM agent_conversation WHERE session_id = #{sessionId} AND operator = #{operator}")
    long countHistory(@Param("operator") String operator, @Param("sessionId") String sessionId);
}
