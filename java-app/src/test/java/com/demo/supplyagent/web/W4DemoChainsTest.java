package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ChatHistoryMessage;
import com.demo.supplyagent.gateway.AgentChatClient;
import com.demo.supplyagent.gateway.UpstreamEnvelope;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4-4 三条离线演示链路(零成本,不依赖真实 DeepSeek key;真实验证链 + 真实 test 库,
 * Agent 对话的上游以 mock 顶替,不发起任何真实模型请求):
 *
 *  A 实时查询:登录 → 库存/销量真实读库 → Agent 对话(来源透传 + 双行落库) → 第二轮续会话历史。
 *  B 补货建议到采购草稿:suggest-replenish 算出 32 → 直接下单被包装倍数规则拦(400 结构化建议 48,
 *    零残留、不占用幂等键) → 按建议 48 建单得 pending 草稿 + 确认流 + 审计 → 同键回放零新增。
 *  C 人工确认与审计:二次确认 → 双表 confirmed + 审计追加 → admin 时间线两事件 → 重复确认 409
 *    不再追加 → viewer 访问确认/审计 403(ACL 边界)。
 *
 * 全程不断言、也不打印任何 token/Authorization/密码;库中行由 @AfterEach 精确清理
 * (conv-% 会话 + 今日 PO 前缀 + 捕获的幂等键),不动种子。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class W4DemoChainsTest {

    private static final String LOGIN_URI = "/api/auth/login";
    private static final String STOCK_URI = "/api/agent/tools/query-stock-level";
    private static final String SALES_URI = "/api/agent/tools/query-sales";
    private static final String SUGGEST_URI = "/api/agent/tools/suggest-replenish";
    private static final String CREATE_URI = "/api/agent/tools/create-purchase-order";
    private static final String CONFIRM_URI = "/api/agent/tools/confirm-po";
    private static final String CHAT_URI = "/api/chat";
    private static final String AUDIT_URI = "/api/audit/history";
    private static final String KEY_HEADER = "Idempotency-Key";
    private static final String DEMO_CONVERSATION = "conv-w4demo-a";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @MockBean
    private AgentChatClient agentChatClient;

    private final Set<String> createdKeys = new LinkedHashSet<>();

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @BeforeEach
    void independentBusinessFactsWithoutAnExistingIntent() {
        // 保留 AUD-001 历史 pending，闭环成功路径使用独立且事实相同的商品。
        jdbc.update("INSERT INTO product (sku_code,name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at) "
                + "SELECT 'TST-W4-AUD-001',name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at "
                + "FROM product WHERE sku_code='AUD-001'");
        jdbc.update("INSERT INTO inventory_snapshot (sku_code,snap_date,stock_qty,created_at,updated_at) "
                + "SELECT 'TST-W4-AUD-001',snap_date,stock_qty,created_at,updated_at FROM inventory_snapshot WHERE sku_code='AUD-001'");
        jdbc.update("INSERT INTO sale_order (order_no,sku_code,order_date,qty,created_at,updated_at) "
                + "SELECT CONCAT('TST-W4-',order_no),'TST-W4-AUD-001',order_date,qty,created_at,updated_at FROM sale_order WHERE sku_code='AUD-001'");
    }

    @AfterEach
    void cleanupDemoRows() {
        jdbc.update("DELETE FROM agent_conversation WHERE session_id LIKE 'conv-%'");
        for (String key : createdKeys) {
            jdbc.update("DELETE FROM idempotency_record WHERE idempotency_key = ?", key);
            jdbc.update("DELETE FROM po_confirmation WHERE idempotency_key = ?", key);
        }
        createdKeys.clear();
        String todayPrefix = "PO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "%";
        jdbc.update("DELETE FROM agent_history WHERE task_key LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_confirmation WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM purchase_po WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_no_seq WHERE seq_date = ?", LocalDate.now());
        jdbc.update("DELETE FROM sale_order WHERE sku_code='TST-W4-AUD-001'");
        jdbc.update("DELETE FROM inventory_snapshot WHERE sku_code='TST-W4-AUD-001'");
        jdbc.update("DELETE FROM product WHERE sku_code='TST-W4-AUD-001'");
    }

    // ---------- 链路 A:实时查询 + 离线 Agent 对话 + 续会话 ----------

    @Test
    void chainARealTimeQueryThenOfflineAgentTurnWithSourcesAndHistory() throws Exception {
        String token = token("admin", "admin123");

        // A1 真实读库:库存与销量来自 test 库种子(最新快照 2026-09-03)
        mvc.perform(tool(STOCK_URI, token, "{\"skus\":[\"TST-W4-AUD-001\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].sku").value("TST-W4-AUD-001"))
                .andExpect(jsonPath("$.data.items[0].availableQty").value(18))
                .andExpect(jsonPath("$.data.items[0].stockLevel").value("low"))
                .andExpect(jsonPath("$.data.items[0].snapDate").value("2026-09-03"));
        mvc.perform(tool(SALES_URI, token, "{\"sku\":\"TST-W4-AUD-001\",\"days\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].totalQty").value(15))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(2.14));

        // A2 Agent 对话(mock 上游):回复/工具调用/来源原样透传,双行落库
        when(agentChatClient.chat(anyString(), anyString(), anyString(), anyString(), any()))
                .thenReturn(upstreamReply("截至 2026-09-03 快照，TST-W4-AUD-001 可售 18 件。",
                        "[{\"tool_call_id\":\"tc-a1\",\"tool\":\"query-stock-level\","
                                + "\"params\":{\"skus\":[\"TST-W4-AUD-001\"]},\"ok\":true,\"round\":1,\"summary\":\"18 件\"}]",
                        "[{\"doc\":\"供应链运营知识\",\"section\":\"库存口径\","
                                + "\"chunk_id\":\"supply-ops.zh#stock-level-1\"}]"));
        MvcResult firstTurn = mvc.perform(chat(token, DEMO_CONVERSATION, "TST-W4-AUD-001 现在还有多少库存？"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.reply").value("截至 2026-09-03 快照，TST-W4-AUD-001 可售 18 件。"))
                .andExpect(jsonPath("$.data.tool_calls[0].tool").value("query-stock-level"))
                .andExpect(jsonPath("$.data.sources[0].chunk_id").value("supply-ops.zh#stock-level-1"))
                .andReturn();

        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT role, content, status, operator, tool_calls_json FROM agent_conversation "
                        + "WHERE session_id = ? ORDER BY id", DEMO_CONVERSATION);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0)).containsEntry("role", "user").containsEntry("operator", "admin")
                .containsEntry("content", "TST-W4-AUD-001 现在还有多少库存？");
        assertThat(rows.get(1)).containsEntry("role", "assistant").containsEntry("status", "success")
                .containsEntry("operator", "admin");
        assertThat((String) rows.get(1).get("tool_calls_json")).contains("query-stock-level");
        // sources 只透传给前端,落库表没有来源列
        List<String> columns = jdbc.queryForList(
                "SELECT COLUMN_NAME FROM information_schema.COLUMNS "
                        + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_conversation'", String.class);
        assertThat(columns).noneMatch(name -> name.toLowerCase().contains("source"));
        String responseBody = firstTurn.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(responseBody).doesNotContain("Bearer").doesNotContain(token).doesNotContain("eyJ");

        // A3 第二轮:离线续会话,把本人历史按时间正序交给上游(数值仍须本轮工具查,由上游承诺)
        doReturn(upstreamReply("按当前库存，建议补货 72 件（每箱24件，须检查现有采购并人工复核）。", "[]", "[]"))
                .when(agentChatClient).chat(anyString(), anyString(), anyString(), anyString(), any());
        mvc.perform(chat(token, DEMO_CONVERSATION, "那补货呢？")).andExpect(status().isOk());

        ArgumentCaptor<List<ChatHistoryMessage>> history = historyCaptor();
        verify(agentChatClient).chat(anyString(), anyString(), eq(DEMO_CONVERSATION), eq("那补货呢？"),
                history.capture());
        assertThat(history.getValue()).extracting(ChatHistoryMessage::role)
                .containsExactly("user", "assistant");
        assertThat(history.getValue()).extracting(ChatHistoryMessage::content)
                .containsExactly("TST-W4-AUD-001 现在还有多少库存？", "截至 2026-09-03 快照，TST-W4-AUD-001 可售 18 件。");
    }

    // ---------- 链路 B:补货建议 → 包装倍数拦截 → 采购草稿 ----------

    @Test
    void chainBReplenishSuggestionBecomesPendingDraftAfterPackagingRule() throws Exception {
        String token = token("admin", "admin123");

        // B1 商品安全库存60，保护期14(含提前期7)：目标90，缺口72，已按24/箱取整。
        MvcResult suggestion = mvc.perform(tool(SUGGEST_URI, token, "{\"skus\":[\"TST-W4-AUD-001\"],\"days\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].currentQty").value(18))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(72))
                .andExpect(jsonPath("$.data.items[0].urgency").value("urgent"))
                .andExpect(jsonPath("$.data.items[0].assessmentStatus").value("ready"))
                .andReturn();
        int suggestedQty = data(suggestion).at("/items/0/suggestedQty").asInt();

        // B2 手工改成73件依然受箱规硬校验；失败零残留且幂等键不被占用。
        String key = register("it-w4demo-b");
        mvc.perform(create(token, key, suggestedQty + 1))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].code").value("qty_not_pkg_multiple"))
                .andExpect(jsonPath("$.data.errors[0].pkgQty").value(24))
                .andExpect(jsonPath("$.data.errors[0].suggestedQty").value(96));
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isZero();

        // B3 人工选择按建议72件建草稿，规则与建议不再冲突。
        MvcResult created = mvc.perform(create(token, key, suggestedQty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.data.items[0].status").value("pending"))
                .andExpect(jsonPath("$.data.items[0].qty").value(72))
                .andReturn();
        String poNo = data(created).at("/items/0/poNo").asText();
        assertThat(jdbc.queryForMap(
                "SELECT status, manual_confirm_required FROM purchase_po WHERE po_no = ?", poNo))
                .containsEntry("status", "pending")
                .containsEntry("manual_confirm_required", true);
        assertThat(jdbc.queryForMap(
                "SELECT status, operator FROM po_confirmation WHERE po_no = ?", poNo))
                .containsEntry("status", "pending_confirmation")
                .containsEntry("operator", "admin");
        assertThat(jdbc.queryForMap(
                "SELECT action_type, after_json FROM agent_history WHERE task_key = ?", poNo))
                .containsEntry("action_type", "po_create")
                .containsEntry("after_json", "{\"status\":\"pending\"}");

        // B4 同键回放:回放首响且零新增(业务行与审计行都不加)
        mvc.perform(create(token, key, suggestedQty))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(true))
                .andExpect(jsonPath("$.data.items[0].poNo").value(poNo));
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE po_no = ?", poNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE task_key = ?", poNo)).isEqualTo(1);
    }

    // ---------- 链路 C:人工确认 → 审计时间线 → 重复确认/越权边界 ----------

    @Test
    void chainCManualConfirmThenAuditTimelineAndBoundaries() throws Exception {
        String admin = token("admin", "admin123");
        String key = register("it-w4demo-c");
        String poNo = data(mvc.perform(create(admin, key, 48)).andExpect(status().isOk()).andReturn())
                .at("/items/0/poNo").asText();

        // C1 二次确认:双表 confirmed,审计追加 po_confirm
        mvc.perform(confirm(admin, poNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.poNo").value(poNo))
                .andExpect(jsonPath("$.data.status").value("confirmed"));
        assertThat(jdbc.queryForObject(
                "SELECT status FROM purchase_po WHERE po_no = ?", String.class, poNo)).isEqualTo("confirmed");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM po_confirmation WHERE po_no = ?", String.class, poNo)).isEqualTo("confirmed");
        assertThat(jdbc.queryForMap(
                "SELECT action_type, before_json, after_json FROM agent_history "
                        + "WHERE task_key = ? AND action_type = 'po_confirm'", poNo))
                .containsEntry("before_json", "{\"status\":\"pending\"}")
                .containsEntry("after_json", "{\"status\":\"confirmed\"}");

        // C2 admin 审计时间线:同一 taskKey 两事件,倒序(confirm 在后)
        MvcResult timeline = mvc.perform(post(AUDIT_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("taskKey", poNo))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].actionType").value("po_confirm"))
                .andExpect(jsonPath("$.data.items[1].actionType").value("po_create"))
                .andReturn();
        String timelineBody = timeline.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(timelineBody).doesNotContain("Bearer").doesNotContain(admin).doesNotContain("eyJ");

        // C3 重复确认:409 且不再追加任何审计行
        mvc.perform(confirm(admin, poNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409));
        assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE task_key = ?", poNo)).isEqualTo(2);

        // C4 越权边界:viewer 既不能确认也不能读审计
        String viewer = token("viewer", "viewer123");
        mvc.perform(confirm(viewer, poNo))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("无权限执行该操作"));
        mvc.perform(post(AUDIT_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + viewer)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private MockHttpServletRequestBuilder tool(String uri, String token, String body) {
        return post(uri)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private MockHttpServletRequestBuilder create(String token, String key, int qty) throws Exception {
        return post(CREATE_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("items", List.of(Map.of("sku", "TST-W4-AUD-001", "qty", qty)))));
    }

    private MockHttpServletRequestBuilder confirm(String token, String poNo) throws Exception {
        return post(CONFIRM_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("poNo", poNo)));
    }

    private MockHttpServletRequestBuilder chat(String token, String conversationId, String message)
            throws Exception {
        return post(CHAT_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(
                        Map.of("conversation_id", conversationId, "message", message)));
    }

    private String token(String username, String password) throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    /** mock 上游:结构与 FastAPI 成功信封同形(含 sources),不发起真实模型请求。 */
    private UpstreamEnvelope upstreamReply(String reply, String toolCallsJson, String sourcesJson) throws Exception {
        String json = "{\"code\":0,\"message\":\"ok\",\"data\":{\"request_id\":\"req-demo\","
                + "\"conversation_id\":\"conv-echo\",\"reply\":" + objectMapper.writeValueAsString(reply)
                + ",\"tool_calls\":" + toolCallsJson + ",\"sources\":" + sourcesJson + "}}";
        return new UpstreamEnvelope(200, objectMapper.readTree(json));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<List<ChatHistoryMessage>> historyCaptor() {
        return ArgumentCaptor.forClass(List.class);
    }

    private long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private String todayPrefix() {
        return "PO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "%";
    }

    private String register(String key) {
        createdKeys.add(key);
        return key;
    }
}
