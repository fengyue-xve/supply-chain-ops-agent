package com.demo.supplyagent.web;

import com.demo.supplyagent.service.tool.ToolAccessService;
import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3-④ 审计时间线查询集成测试（真实 SecurityFilterChain + 真实 SQL + 真实 test 库）。
 * 覆盖:匿名 401 / viewer 403 / admin 默认分页 / 固定排序 / 五等值过滤 / NULL 列序列化 /
 * 空白过滤等同未传 / 超宽 400 / page,size 边界 / 未知 actionType 空结果 / 查询零写入 /
 * 只返回请求页 / 既有信封口径回归。
 * 夹具行统一 task_key 前缀 it-w3d-,@AfterEach 按前缀精确清理(种子 6 行不动)。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class AuditHistoryIntegrationTest {

    private static final String AUDIT_URI = "/api/audit/history";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String FIXTURE_PREFIX = "it-w3d-";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @AfterEach
    void cleanupFixtures() {
        jdbc.update("DELETE FROM agent_history WHERE task_key LIKE ?", FIXTURE_PREFIX + "%");
    }

    // ---------- 权限:匿名 401 / viewer 403(后端独立拒绝,前端 v-if 只是体验层) ----------

    @Test
    void anonymousRequestIs401WithThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(auditRequest(null, "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void viewerRequestIs403WithThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(auditRequest(token("viewer", "viewer123"), "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(ToolAccessService.ACCESS_DENIED_MESSAGE))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    // ---------- admin 默认分页与固定排序 ----------

    @Test
    void adminGetsDefaultPaging() throws Exception {
        MvcResult result = mvc.perform(auditRequest(adminToken(), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(6)))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()", lessThanOrEqualTo(20)))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void resultsOrderedByExecutedAtDescThenIdDesc() throws Exception {
        MvcResult result = mvc.perform(auditRequest(adminToken(), "{\"size\":100}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = data(result).path("items");
        assertThat(items.size()).isGreaterThanOrEqualTo(6);

        String prevTime = null;
        long prevId = Long.MAX_VALUE;
        for (JsonNode item : items) {
            String executedAt = item.path("executedAt").asText();
            // SQL 内 DATE_FORMAT 统一口径:yyyy-MM-dd HH:mm:ss(字符串比较即时间序)
            assertThat(executedAt).matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}");
            long id = item.path("id").asLong();
            if (prevTime != null) {
                int byTime = executedAt.compareTo(prevTime);
                assertThat(byTime).isLessThanOrEqualTo(0);
                if (byTime == 0) {
                    assertThat(id).isLessThan(prevId);
                }
            }
            prevTime = executedAt;
            prevId = id;
        }
        // 种子:09-03 的 id=6 必须排在 09-02 的 id=5 之前
        int index6 = indexOfId(items, 6);
        int index5 = indexOfId(items, 5);
        assertThat(index6).isGreaterThanOrEqualTo(0);
        assertThat(index5).isGreaterThan(index6);
    }

    // ---------- 过滤:五个字段等值命中 + AND 语义 ----------

    @Test
    void taskKeyFilterReturnsSeedRow() throws Exception {
        MvcResult result = mvc.perform(auditRequest(adminToken(), "{\"taskKey\":\"PO20260902001\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(5))
                .andExpect(jsonPath("$.data.items[0].executedAt").value("2026-09-02 11:21:00"))
                .andExpect(jsonPath("$.data.items[0].actionType").value("po_create"))
                .andExpect(jsonPath("$.data.items[0].who").value("agent"))
                .andExpect(jsonPath("$.data.items[0].taskKey").value("PO20260902001"))
                .andReturn();
        assertThreeFieldEnvelope(result);
        JsonNode item = data(result).path("items").get(0);
        assertThat(item.path("afterJson").asText())
                .isEqualTo("{\"poNo\":\"PO20260902001\",\"status\":\"pending\",\"qty\":96}");
    }

    @Test
    void actionTypeOperatorRequestIdConversationIdFiltersReturnOnlyMatchingRows() throws Exception {
        long idA = insertFixture("fix-a", "it_w3d_confirm", "admin", "it-w3d-opA",
                "it-w3d-rid-1", "it-w3d-conv-1", LocalDateTime.of(2026, 9, 10, 8, 30, 0));
        long idB = insertFixture("fix-b", "it_w3d_create", "agent", null,
                "it-w3d-rid-2", "it-w3d-conv-2", LocalDateTime.of(2026, 9, 10, 8, 31, 0));

        mvc.perform(auditRequest(adminToken(),
                        "{\"actionType\":\"it_w3d_confirm\",\"operator\":\"it-w3d-opA\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) idA))
                .andExpect(jsonPath("$.data.items[0].conversationId").value("it-w3d-conv-1"))
                .andExpect(jsonPath("$.data.items[0].toolCallId").value("tc-it-w3d-rid-1"));

        mvc.perform(auditRequest(adminToken(), "{\"requestId\":\"it-w3d-rid-1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) idA));

        mvc.perform(auditRequest(adminToken(), "{\"conversationId\":\"it-w3d-conv-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) idB));

        mvc.perform(auditRequest(adminToken(), "{\"taskKey\":\"it-w3d-fix-a\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value((int) idA));

        // AND 语义:两个条件各自命中一行,组合后零命中
        mvc.perform(auditRequest(adminToken(),
                        "{\"actionType\":\"it_w3d_confirm\",\"requestId\":\"it-w3d-rid-2\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ---------- NULL 列:显式 null 进信封(前端显示 —) ----------

    @Test
    void nullColumnsSerializeAsExplicitNull() throws Exception {
        insertFixture("fix-null", "it_w3d_nullop", "task", null, null, null,
                LocalDateTime.of(2026, 9, 10, 9, 0, 0));

        MvcResult result = mvc.perform(auditRequest(adminToken(), "{\"actionType\":\"it_w3d_nullop\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].operator").value(nullValue()))
                .andReturn();
        JsonNode item = data(result).path("items").get(0);
        assertThat(item.has("operator")).isTrue();
        assertThat(item.get("operator").isNull()).isTrue();
        assertThat(item.get("requestId").isNull()).isTrue();
        assertThat(item.get("beforeJson").isNull()).isTrue();
        assertThat(item.get("statsJson").isNull()).isTrue();

        // 种子行 operator=NULL 同样不炸序列化
        MvcResult seed = mvc.perform(auditRequest(adminToken(), "{\"taskKey\":\"PO20260902001\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode seedItem = data(seed).path("items").get(0);
        assertThat(seedItem.has("operator")).isTrue();
        assertThat(seedItem.get("operator").isNull()).isTrue();
    }

    // ---------- 空白过滤等同未传 ----------

    @Test
    void blankFilterValuesAreTreatedAsAbsent() throws Exception {
        long baseline = data(mvc.perform(auditRequest(adminToken(), "{}"))
                .andExpect(status().isOk())
                .andReturn()).path("total").asLong();

        long blanked = data(mvc.perform(auditRequest(adminToken(),
                        "{\"taskKey\":\"   \",\"actionType\":\"\",\"operator\":\"   \","
                                + "\"requestId\":\" \",\"conversationId\":\"\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andReturn()).path("total").asLong();

        assertThat(blanked).isEqualTo(baseline);
    }

    // ---------- 超宽过滤值 400(trim 后按列宽判定,不截断) ----------

    @Test
    void oversizedFilterValuesAre400() throws Exception {
        expect400("{\"actionType\":\"" + "a".repeat(41) + "\"}", "actionType 不能超过 40 字符");
        expect400("{\"taskKey\":\"" + "k".repeat(81) + "\"}", "taskKey 不能超过 80 字符");
        expect400("{\"operator\":\"" + "o".repeat(65) + "\"}", "operator 不能超过 64 字符");
        expect400("{\"requestId\":\"" + "r".repeat(65) + "\"}", "requestId 不能超过 64 字符");
        expect400("{\"conversationId\":\"" + "c".repeat(81) + "\"}", "conversationId 不能超过 80 字符");
        // 首尾空白先 trim 再判宽:40 字符合法,46 字符(含空白)同样合法
        mvc.perform(auditRequest(adminToken(), "{\"taskKey\":\"  " + "k".repeat(40) + "  \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    // ---------- page/size 边界与非法值 ----------

    @Test
    void pageAndSizeBoundaries() throws Exception {
        expect400("{\"page\":0}", "page 必须大于等于 1");
        expect400("{\"page\":-3}", "page 必须大于等于 1");
        expect400("{\"size\":0}", "size 必须在 1~100 之间");
        expect400("{\"size\":101}", "size 必须在 1~100 之间");
        // 类型错(字符串塞给 Integer)走 HttpMessageNotReadable -> 400 信封
        MvcResult malformed = mvc.perform(auditRequest(adminToken(), "{\"page\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"))
                .andReturn();
        assertThreeFieldEnvelope(malformed);

        mvc.perform(auditRequest(adminToken(), "{\"page\":1,\"size\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
        mvc.perform(auditRequest(adminToken(), "{\"page\":1,\"size\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(100));
    }

    // ---------- 未知 actionType 不做枚举白名单:200 空结果 ----------

    @Test
    void unknownActionTypeReturnsEmptyResultNotError() throws Exception {
        mvc.perform(auditRequest(adminToken(), "{\"actionType\":\"it_w3d_no_such_action\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ---------- 查询零写入 ----------

    @Test
    void queryPerformsZeroWrites() throws Exception {
        Map<String, Long> before = snapshotTableState();
        mvc.perform(auditRequest(adminToken(), "{}")).andExpect(status().isOk());
        mvc.perform(auditRequest(adminToken(), "{\"taskKey\":\"PO20260902001\"}"))
                .andExpect(status().isOk());
        mvc.perform(auditRequest(adminToken(), "{\"page\":9999,\"size\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(0));
        Map<String, Long> after = snapshotTableState();
        assertThat(after).isEqualTo(before);
    }

    // ---------- 只返回请求的 page/size(count 与 page 同 WHERE) ----------

    @Test
    void onlyRequestedPageAndSizeRowsReturned() throws Exception {
        long idThird = insertFixture("page-3", "it_w3d_page", "task", null, null, null,
                LocalDateTime.of(2026, 9, 10, 10, 0, 2));
        long idSecond = insertFixture("page-2", "it_w3d_page", "task", null, null, null,
                LocalDateTime.of(2026, 9, 10, 10, 0, 1));
        long idFirst = insertFixture("page-1", "it_w3d_page", "task", null, null, null,
                LocalDateTime.of(2026, 9, 10, 10, 0, 0));

        MvcResult page1 = mvc.perform(auditRequest(adminToken(),
                        "{\"actionType\":\"it_w3d_page\",\"size\":2,\"page\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn();
        JsonNode items1 = data(page1).path("items");
        assertThat(items1.get(0).path("id").asLong()).isEqualTo(idThird);
        assertThat(items1.get(1).path("id").asLong()).isEqualTo(idSecond);

        MvcResult page2 = mvc.perform(auditRequest(adminToken(),
                        "{\"actionType\":\"it_w3d_page\",\"size\":2,\"page\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        assertThat(data(page2).path("items").get(0).path("id").asLong()).isEqualTo(idFirst);

        mvc.perform(auditRequest(adminToken(), "{\"actionType\":\"it_w3d_page\",\"size\":2,\"page\":3}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(0));
    }

    // ---------- 既有 401/404/200 信封口径不受新 matcher 影响 ----------

    @Test
    void existingEnvelopeContractsUnaffected() throws Exception {
        String admin = adminToken();

        MvcResult stock = mvc.perform(post("/api/agent/tools/query-stock-level")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skus\":[\"AUD-001\"]}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        assertThreeFieldEnvelope(stock);

        MvcResult anonymousChat = mvc.perform(post("/api/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"hi\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andReturn();
        assertThreeFieldEnvelope(anonymousChat);

        MvcResult notFound = mvc.perform(post("/api/audit/history-typo")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("接口不存在"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        assertThreeFieldEnvelope(notFound);
    }

    // ---------- helpers ----------

    private String adminToken() throws Exception {
        return token("admin", "admin123");
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

    private MockHttpServletRequestBuilder auditRequest(String token, String body) {
        MockHttpServletRequestBuilder request = post(AUDIT_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    private void expect400(String body, String expectedMessage) throws Exception {
        MvcResult result = mvc.perform(auditRequest(adminToken(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value(expectedMessage))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    /** 顶层信封恒 {code,message,data} 三字段(用字节读取,避免无 charset 响应的解码陷阱)。 */
    private void assertThreeFieldEnvelope(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(root.size()).isEqualTo(3);
        assertThat(root.has("code")).isTrue();
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("data")).isTrue();
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("data");
    }

    /**
     * 插入一条审计夹具行(仅测试库;@AfterEach 按 task_key 前缀清理)。
     * 返回自增 id:按 task_key 回查(LAST_INSERT_ID() 是连接级,JdbcTemplate 不保证同连接)。
     */
    private long insertFixture(String suffix, String actionType, String who, String operator,
                               String requestId, String conversationId, LocalDateTime executedAt) {
        String taskKey = FIXTURE_PREFIX + suffix;
        jdbc.update("INSERT INTO agent_history (task_key, executed_at, action_type, who, operator, "
                        + "before_json, after_json, stats_json, requested_at, request_id, conversation_id, "
                        + "tool_call_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, NULL, ?, NULL, ?, ?, ?, ?, ?, ?)",
                taskKey, executedAt, actionType, who, operator,
                "{\"status\":\"confirmed\"}", executedAt, requestId, conversationId,
                requestId == null ? null : "tc-" + requestId, executedAt, executedAt);
        return jdbc.queryForObject("SELECT id FROM agent_history WHERE task_key = ?", Long.class, taskKey);
    }

    private Map<String, Long> snapshotTableState() {
        Map<String, Long> state = new LinkedHashMap<>();
        state.put("agent_history_count", countRows("SELECT COUNT(*) FROM agent_history"));
        state.put("agent_history_max_id",
                jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM agent_history", Long.class));
        state.put("idempotency_record_count", countRows("SELECT COUNT(*) FROM idempotency_record"));
        state.put("purchase_po_count", countRows("SELECT COUNT(*) FROM purchase_po"));
        state.put("po_confirmation_count", countRows("SELECT COUNT(*) FROM po_confirmation"));
        state.put("agent_conversation_count", countRows("SELECT COUNT(*) FROM agent_conversation"));
        return state;
    }

    private long countRows(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }

    private int indexOfId(JsonNode items, long id) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).path("id").asLong() == id) {
                return i;
            }
        }
        return -1;
    }
}
