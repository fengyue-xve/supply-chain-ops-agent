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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.lessThanOrEqualTo;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4 待确认采购单查询集成测试（真实 SecurityFilterChain + 真实 SQL + 真实 test 库）。
 * 覆盖:匿名 401 / viewer 403 / admin 默认分页 / 仅 pending 可见 / 固定排序 /
 * page,size 边界与格式错误 400 信封 / 翻页切片与全量一致 / 查询零写入 / 既有信封口径回归。
 * 夹具行 po_no 统一前缀 it-w4po-,@AfterEach 按前缀精确清理(种子行不动)。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class PoPendingIntegrationTest {

    private static final String PO_URI = "/api/po/pending";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String FIXTURE_PREFIX = "it-w4po-";
    private static final String SEED_PENDING_NEWER = "PO20260903001";
    private static final String SEED_PENDING_OLDER = "PO20260902001";
    private static final String SEED_CONFIRMED = "PO20260812001";
    private static final String SEED_COMPLETED = "PO20260715001";

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
        jdbc.update("DELETE FROM purchase_po WHERE po_no LIKE ?", FIXTURE_PREFIX + "%");
    }

    // ---------- 权限:匿名 401 / viewer 403(后端独立拒绝,前端 v-if 只是体验层) ----------

    @Test
    void anonymousRequestIs401WithThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(poRequest(null, "{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void viewerRequestIs403WithThreeFieldEnvelope() throws Exception {
        MvcResult result = mvc.perform(poRequest(token("viewer", "viewer123"), "{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(403))
                .andExpect(jsonPath("$.message").value(ToolAccessService.ACCESS_DENIED_MESSAGE))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    // ---------- admin 默认分页 / 种子行形状 / 仅 pending 可见 ----------

    @Test
    void adminGetsDefaultPaging() throws Exception {
        MvcResult result = mvc.perform(poRequest(adminToken(), "{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.items").isArray())
                .andExpect(jsonPath("$.data.items.length()", lessThanOrEqualTo(20)))
                .andReturn();
        assertThreeFieldEnvelope(result);
    }

    @Test
    void onlyPendingRowsVisibleWithExactItemShape() throws Exception {
        insertFixture("confirmed-hidden", "confirmed", "AUD-001", 48,
                LocalDateTime.of(2026, 9, 10, 11, 0, 0));

        MvcResult result = mvc.perform(poRequest(adminToken(), "{\"size\":100}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = data(result).path("items");

        // 种子:两条 pending 可见,字段形状锁定(恰 6 字段)
        JsonNode seed = findItem(items, SEED_PENDING_NEWER);
        assertThat(seed).isNotNull();
        assertThat(seed.size()).isEqualTo(6);
        assertThat(seed.path("skuCode").asText()).isEqualTo("CHG-001");
        assertThat(seed.path("qty").asInt()).isEqualTo(120);
        assertThat(seed.path("status").asText()).isEqualTo("pending");
        assertThat(seed.path("manualConfirmRequired").asBoolean()).isTrue();
        assertThat(seed.path("createdAt").asText()).isEqualTo("2026-09-03 09:40:00");
        assertThat(findItem(items, SEED_PENDING_OLDER)).isNotNull();

        // 非 pending(confirmed/completed 种子、confirmed 夹具)一律不可见
        assertThat(findItem(items, SEED_CONFIRMED)).isNull();
        assertThat(findItem(items, SEED_COMPLETED)).isNull();
        assertThat(findItem(items, FIXTURE_PREFIX + "confirmed-hidden")).isNull();
    }

    // ---------- 固定排序 created_at DESC, id DESC ----------

    @Test
    void resultsOrderedByCreatedAtDescThenIdDesc() throws Exception {
        insertFixture("ord-1", "pending", "AUD-001", 12,
                LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        insertFixture("ord-2", "pending", "AUD-002", 12,
                LocalDateTime.of(2026, 9, 10, 10, 0, 1));
        insertFixture("ord-3", "pending", "CHG-001", 12,
                LocalDateTime.of(2026, 9, 10, 10, 0, 2));

        MvcResult result = mvc.perform(poRequest(adminToken(), "{\"size\":100}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = data(result).path("items");

        int idxThird = indexOfPoNo(items, FIXTURE_PREFIX + "ord-3");
        int idxSecond = indexOfPoNo(items, FIXTURE_PREFIX + "ord-2");
        int idxFirst = indexOfPoNo(items, FIXTURE_PREFIX + "ord-1");
        int idxSeedNewer = indexOfPoNo(items, SEED_PENDING_NEWER);
        int idxSeedOlder = indexOfPoNo(items, SEED_PENDING_OLDER);
        assertThat(idxThird).isGreaterThanOrEqualTo(0);
        // 09-10 夹具全部排在 09-03 种子之前(时间倒序)
        assertThat(idxThird).isLessThan(idxSeedNewer);
        assertThat(idxFirst).isLessThan(idxSeedNewer);
        assertThat(idxSeedNewer).isLessThan(idxSeedOlder);
        // 夹具内部按 created_at 倒序
        assertThat(idxThird).isLessThan(idxSecond);
        assertThat(idxSecond).isLessThan(idxFirst);
    }

    // ---------- page/size 边界与格式错误 400 ----------

    @Test
    void pageAndSizeBoundaries() throws Exception {
        expect400("{\"page\":0}", "page 必须大于等于 1");
        expect400("{\"page\":-3}", "page 必须大于等于 1");
        expect400("{\"size\":0}", "size 必须在 1~100 之间");
        expect400("{\"size\":101}", "size 必须在 1~100 之间");

        MvcResult malformed = mvc.perform(poRequest(adminToken(), "{\"page\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"))
                .andReturn();
        assertThreeFieldEnvelope(malformed);

        // 越界页码:200 空页(total 仍为全量,同过滤口径)
        mvc.perform(poRequest(adminToken(), "{\"page\":9999,\"size\":100}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(2)))
                .andExpect(jsonPath("$.data.items.length()").value(0));

        mvc.perform(poRequest(adminToken(), "{\"page\":1,\"size\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.items.length()").value(1));
    }

    // ---------- 翻页切片与全量列表逐一致 ----------

    @Test
    void paginationSlicesMatchFullList() throws Exception {
        insertFixture("pg-1", "pending", "AUD-001", 12,
                LocalDateTime.of(2026, 9, 10, 10, 0, 0));
        insertFixture("pg-2", "pending", "AUD-002", 12,
                LocalDateTime.of(2026, 9, 10, 10, 0, 1));
        insertFixture("pg-3", "pending", "CHG-001", 12,
                LocalDateTime.of(2026, 9, 10, 10, 0, 2));

        List<String> full = poNosOf(itemsOf(poRequest(adminToken(), "{\"size\":100}")));
        assertThat(full.size()).isGreaterThanOrEqualTo(5);
        assertThat(full.size()).isLessThanOrEqualTo(100);

        // page=2&size=2 恰为全量的 [2,4) 切片
        List<String> page2 = poNosOf(itemsOf(
                poRequest(adminToken(), "{\"page\":2,\"size\":2}")));
        assertThat(page2).isEqualTo(full.subList(2, 4));

        // 按 size=2 逐页拼合 === 全量(顺序与内容)
        List<String> collected = new ArrayList<>();
        int pages = (full.size() + 1) / 2;
        for (int page = 1; page <= pages; page++) {
            collected.addAll(poNosOf(itemsOf(
                    poRequest(adminToken(), "{\"page\":" + page + ",\"size\":2}"))));
        }
        assertThat(collected).isEqualTo(full);
    }

    // ---------- 查询零写入 ----------

    @Test
    void queryPerformsZeroWrites() throws Exception {
        Map<String, Long> before = snapshotTableState();
        mvc.perform(poRequest(adminToken(), "{}")).andExpect(status().isOk());
        mvc.perform(poRequest(adminToken(), "{\"size\":100}")).andExpect(status().isOk());
        mvc.perform(poRequest(adminToken(), "{\"page\":9999,\"size\":100}"))
                .andExpect(status().isOk());
        Map<String, Long> after = snapshotTableState();
        assertThat(after).isEqualTo(before);
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

        MvcResult notFound = mvc.perform(post("/api/po/pending-typo")
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

    private MockHttpServletRequestBuilder poRequest(String token, String body) {
        MockHttpServletRequestBuilder request = post(PO_URI)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        return request;
    }

    private void expect400(String body, String expectedMessage) throws Exception {
        MvcResult result = mvc.perform(poRequest(adminToken(), body))
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

    private JsonNode itemsOf(MockHttpServletRequestBuilder builder) throws Exception {
        MvcResult result = mvc.perform(builder).andExpect(status().isOk()).andReturn();
        return data(result).path("items");
    }

    private List<String> poNosOf(JsonNode items) {
        List<String> poNos = new ArrayList<>();
        items.forEach(item -> poNos.add(item.path("poNo").asText()));
        return poNos;
    }

    private JsonNode findItem(JsonNode items, String poNo) {
        int index = indexOfPoNo(items, poNo);
        return index < 0 ? null : items.get(index);
    }

    private int indexOfPoNo(JsonNode items, String poNo) {
        for (int i = 0; i < items.size(); i++) {
            if (poNo.equals(items.get(i).path("poNo").asText())) {
                return i;
            }
        }
        return -1;
    }

    /** 插入一条 purchase_po 夹具行(仅测试库;@AfterEach 按 po_no 前缀清理)。 */
    private void insertFixture(String suffix, String status, String sku, int qty,
                               LocalDateTime createdAt) {
        jdbc.update("INSERT INTO purchase_po (po_no, sku_code, qty, status, "
                        + "manual_confirm_required, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, 1, ?, ?)",
                FIXTURE_PREFIX + suffix, sku, qty, status, createdAt, createdAt);
    }

    private Map<String, Long> snapshotTableState() {
        Map<String, Long> state = new LinkedHashMap<>();
        state.put("purchase_po_count", countRows("SELECT COUNT(*) FROM purchase_po"));
        state.put("purchase_po_max_id",
                jdbc.queryForObject("SELECT COALESCE(MAX(id), 0) FROM purchase_po", Long.class));
        state.put("po_confirmation_count", countRows("SELECT COUNT(*) FROM po_confirmation"));
        state.put("idempotency_record_count", countRows("SELECT COUNT(*) FROM idempotency_record"));
        state.put("agent_history_count", countRows("SELECT COUNT(*) FROM agent_history"));
        state.put("agent_conversation_count", countRows("SELECT COUNT(*) FROM agent_conversation"));
        return state;
    }

    private long countRows(String sql) {
        Long count = jdbc.queryForObject(sql, Long.class);
        return count == null ? 0 : count;
    }
}
