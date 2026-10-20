package com.demo.supplyagent.web;

import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3-③ confirm-po 集成测试（真实链路 + 真实 test 库）。
 * 覆盖：确认后双表同迁 + 审计快照；重复确认 409 带当前状态且不追加审计；404；
 * 确认时 SKU 已下架 → failed + cancelled + 审计后 409（状态迁移已提交）；请求体形状 400；
 * 并发双确认恰一方成功。清理与创建侧同构（精确删 + 今天业务日前缀自愈清扫）。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ConfirmPoIntegrationTest {

    private static final String CONFIRM_URI = "/api/agent/tools/confirm-po";
    private static final String CREATE_URI = "/api/agent/tools/create-purchase-order";
    private static final String LOGIN_URI = "/api/auth/login";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private final Set<String> createdKeys = new LinkedHashSet<>();

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @BeforeEach
    void isolatedProductsWithoutHistoricalPendingIntent() {
        // 原种子已有 AUD-001/CHG-001 的 pending；成功用例独立主档，不修改或删除业务种子。
        jdbc.update("INSERT INTO product (sku_code,name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at) "
                + "SELECT CONCAT('TST-PO-',sku_code),name,category,pkg_qty,shelf_status,safety_stock,lead_days,created_at,updated_at "
                + "FROM product WHERE sku_code IN ('AUD-001','CHG-001') "
                + "ON DUPLICATE KEY UPDATE pkg_qty=VALUES(pkg_qty),shelf_status=VALUES(shelf_status)");
    }

    @AfterEach
    void cleanupCreatedRows() {
        // 自愈:审计表回滚用例若中途失败未复原,先恢复表名再清理
        if (countRows("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'agent_history_it_bak'") > 0) {
            jdbc.execute("RENAME TABLE agent_history_it_bak TO agent_history");
        }
        for (String key : createdKeys) {
            jdbc.update("DELETE FROM idempotency_record WHERE idempotency_key = ?", key);
            jdbc.update("DELETE FROM po_confirmation WHERE idempotency_key = ?", key);
        }
        createdKeys.clear();
        String todayPrefix = todayPrefix();
        jdbc.update("DELETE FROM agent_history WHERE task_key LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_confirmation WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM purchase_po WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_no_seq WHERE seq_date = ?", LocalDate.now());
        // 下架夹具复原（种子值 on_shelf）
        jdbc.update("UPDATE product SET shelf_status = 'on_shelf' WHERE sku_code = 'AUD-002'");
        jdbc.update("DELETE FROM product WHERE sku_code IN ('TST-PO-AUD-001','TST-PO-CHG-001')");
    }

    // ---------- 成功确认:双表同迁 + 审计快照 ----------

    @Test
    void confirmTransitionsDraftToConfirmedWithAudit() throws Exception {
        String token = adminToken();
        String poNo = createPo(token, "it-w3c-cpf-1", "TST-PO-AUD-001", 48);

        mvc.perform(confirmRequest(token, poNo)
                        .header("X-Request-Id", "it-w3c-cpf-rid-1")
                        .header("X-Conversation-Id", "it-w3c-cpf-conv-1")
                        .header("X-Tool-Call-Id", "it-w3c-cpf-tc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.poNo").value(poNo))
                .andExpect(jsonPath("$.data.sku").value("TST-PO-AUD-001"))
                .andExpect(jsonPath("$.data.qty").value(48))
                .andExpect(jsonPath("$.data.status").value("confirmed"));

        assertThat(jdbc.queryForObject(
                "SELECT status FROM po_confirmation WHERE po_no = ?", String.class, poNo))
                .isEqualTo("confirmed");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM purchase_po WHERE po_no = ?", String.class, poNo))
                .isEqualTo("confirmed");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action_type, who, operator, before_json, after_json, request_id, conversation_id, tool_call_id "
                        + "FROM agent_history WHERE task_key = ? AND action_type = 'po_confirm'", poNo);
        assertThat(audit)
                .containsEntry("who", "admin")
                .containsEntry("operator", "admin")
                .containsEntry("before_json", "{\"status\":\"pending\"}")
                .containsEntry("after_json", "{\"status\":\"confirmed\"}")
                .containsEntry("request_id", "it-w3c-cpf-rid-1")
                .containsEntry("conversation_id", "it-w3c-cpf-conv-1")
                .containsEntry("tool_call_id", "it-w3c-cpf-tc-1");
    }

    // ---------- 重复确认:409 + 当前状态,不追加审计 ----------

    @Test
    void duplicateConfirmIs409WithCurrentStatusAndNoExtraAudit() throws Exception {
        String token = adminToken();
        String poNo = createPo(token, "it-w3c-cpf-dup", "TST-PO-AUD-001", 48);

        mvc.perform(confirmRequest(token, poNo)).andExpect(status().isOk());
        MvcResult conflict = mvc.perform(confirmRequest(token, poNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("采购单当前状态为 confirmed，不能执行确认"))
                .andExpect(jsonPath("$.data.poNo").value(poNo))
                .andExpect(jsonPath("$.data.status").value("confirmed"))
                .andReturn();
        // 409 的结构化补充(poNo/status)只藏在 data 内,顶层仍是 {code,message,data}
        assertThreeFieldEnvelope(conflict);

        assertThat(countRows("SELECT COUNT(*) FROM agent_history "
                + "WHERE task_key = ? AND action_type LIKE 'po_confirm%'", poNo)).isEqualTo(1);
    }

    // ---------- 单号不存在:404 ----------

    @Test
    void unknownPoNoIs404() throws Exception {
        mvc.perform(confirmRequest(adminToken(), "PO20990101999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(404))
                .andExpect(jsonPath("$.message").value("采购单不存在"));
    }

    // ---------- 确认时 SKU 已下架:failed + cancelled + 审计,随后 409 ----------

    @Test
    void offShelfAtConfirmFailsDraftAndCancelsPo() throws Exception {
        String token = adminToken();
        String poNo = createPo(token, "it-w3c-cpf-shelf", "AUD-002", 6);
        jdbc.update("UPDATE product SET shelf_status = 'off_shelf' WHERE sku_code = 'AUD-002'");

        MvcResult stale = mvc.perform(confirmRequest(token, poNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.data.status").value("failed"))
                .andExpect(jsonPath("$.message").value("SKU 不存在或已下架，采购单已置为 failed 并取消"))
                .andReturn();
        assertThreeFieldEnvelope(stale);

        // HTTP 409 之前,failed+cancelled+审计必须是已提交终态(提交语义不变量)
        assertThat(jdbc.queryForMap(
                "SELECT status, reason FROM po_confirmation WHERE po_no = ?", poNo))
                .containsEntry("status", "failed")
                .containsEntry("reason", "SKU 不存在或已下架");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM purchase_po WHERE po_no = ?", String.class, poNo))
                .isEqualTo("cancelled");

        // 唯一审计事件:恰好一条 po_confirm_fail,且不存在任何 po_confirm 成功事件
        assertThat(countRows("SELECT COUNT(*) FROM agent_history "
                + "WHERE task_key = ? AND action_type = 'po_confirm_fail'", poNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM agent_history "
                + "WHERE task_key = ? AND action_type = 'po_confirm'", poNo)).isZero();
        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action_type, before_json, after_json FROM agent_history "
                        + "WHERE task_key = ? AND action_type = 'po_confirm_fail'", poNo);
        assertThat(audit)
                .containsEntry("before_json", "{\"status\":\"pending\"}")
                .containsEntry("after_json", "{\"status\":\"cancelled\"}");

        // 不可恢复状态已提交:再确认仍是 409(failed 态),而不是 404/500
        mvc.perform(confirmRequest(token, poNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.status").value("failed"));
    }

    // ---------- 请求体形状 400 ----------

    @Test
    void confirmBodyShapeViolationsAre400() throws Exception {
        String token = adminToken();
        mvc.perform(post(CONFIRM_URI).header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("请求体不能为空"));
        mvc.perform(confirmRequest(token, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("poNo 不能为空"));
        mvc.perform(confirmRequest(token, "P".repeat(41)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("poNo 不能超过 40 字符"));
    }

    // ---------- 请求头校验:可选但给了就必须合法;空值/超长不落库 ----------

    @Test
    void confirmHeaderValidationIs400() throws Exception {
        String token = adminToken();
        String poNo = "PO20990101999";

        mvc.perform(confirmRequest(token, poNo).header("Idempotency-Key", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key 不能为空"));
        mvc.perform(confirmRequest(token, poNo).header("Idempotency-Key", "k".repeat(81)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key 不能超过 80 字符"));
        mvc.perform(confirmRequest(token, poNo).header("Idempotency-Key", "bad key!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key 仅允许字母、数字与 _ . : -"));
        mvc.perform(confirmRequest(token, poNo).header("X-Request-Id", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Request-Id 不能为空"));
        mvc.perform(confirmRequest(token, poNo).header("X-Conversation-Id", "c".repeat(81)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Conversation-Id 不能超过 80 字符"));
        mvc.perform(confirmRequest(token, poNo).header("X-Tool-Call-Id", "t".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Tool-Call-Id 不能超过 64 字符"));

        // 合法 Idempotency-Key 不参与 confirm 语义:正常确认,且不产生幂等登记行
        String realPoNo = createPo(token, "it-w3c-cpf-header-ok", "TST-PO-AUD-001", 48);
        mvc.perform(confirmRequest(token, realPoNo).header("Idempotency-Key", "it-w3c-cpf-opt-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("confirmed"));
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?",
                "it-w3c-cpf-opt-key")).isZero();
    }

    // ---------- 审计写入失败:整事务回滚 + 500,绝不半提交 ----------

    @Test
    void auditFailureRollsBackStaleConfirmAndReturns500() throws Exception {
        String token = adminToken();
        String poNo = createPo(token, "it-w3c-cpf-auditfail", "AUD-002", 6);
        jdbc.update("UPDATE product SET shelf_status = 'off_shelf' WHERE sku_code = 'AUD-002'");

        // 制造审计写入失败:临时移走审计表(insert 报 DataAccessException)
        jdbc.execute("RENAME TABLE agent_history TO agent_history_it_bak");
        try {
            MvcResult failure = mvc.perform(confirmRequest(token, poNo))
                    .andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value(500))
                    .andExpect(jsonPath("$.message").value("数据库访问异常，请稍后重试"))
                    .andReturn();
            assertThreeFieldEnvelope(failure);

            // 审计失败 → 整体回滚:failed/cancelled 均不得落库,确认流保持可重试态
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM po_confirmation WHERE po_no = ?", String.class, poNo))
                    .isEqualTo("pending_confirmation");
            assertThat(jdbc.queryForObject(
                    "SELECT status FROM purchase_po WHERE po_no = ?", String.class, poNo))
                    .isEqualTo("pending");
        } finally {
            jdbc.execute("RENAME TABLE agent_history_it_bak TO agent_history");
        }

        // 回滚后仍可正常失败迁移(证明上一步是干净回滚,不是被永久破坏)
        mvc.perform(confirmRequest(token, poNo))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.status").value("failed"));
    }

    // ---------- 并发双确认:恰一方成功 ----------

    @Test
    void concurrentDoubleConfirmExactlyOneWins() throws Exception {
        String token = adminToken();
        String poNo = createPo(token, "it-w3c-cpf-race", "AUD-003", 12);

        List<MvcResult> results = runConcurrently(List.of(
                () -> mvc.perform(confirmRequest(token, poNo)).andReturn(),
                () -> mvc.perform(confirmRequest(token, poNo)).andReturn()));

        List<Integer> statuses = new ArrayList<>();
        results.forEach(r -> statuses.add(r.getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        assertThat(jdbc.queryForObject(
                "SELECT status FROM po_confirmation WHERE po_no = ?", String.class, poNo))
                .isEqualTo("confirmed");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM purchase_po WHERE po_no = ?", String.class, poNo))
                .isEqualTo("confirmed");
        assertThat(countRows("SELECT COUNT(*) FROM agent_history "
                + "WHERE task_key = ? AND action_type = 'po_confirm'", poNo)).isEqualTo(1);
    }

    // ---------- helpers ----------

    private String adminToken() throws Exception {
        MvcResult result = mvc.perform(post(LOGIN_URI)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private String createPo(String token, String key, String sku, int qty) throws Exception {
        createdKeys.add(key);
        String body = objectMapper.writeValueAsString(
                Map.of("items", List.of(Map.of("sku", sku, "qty", qty))));
        MvcResult result = mvc.perform(post(CREATE_URI)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("items").get(0).path("poNo").asText();
    }

    private MockHttpServletRequestBuilder confirmRequest(String token, String poNo) throws Exception {
        return post(CONFIRM_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of("poNo", poNo)));
    }

    private List<MvcResult> runConcurrently(List<Callable<MvcResult>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        CountDownLatch ready = new CountDownLatch(calls.size());
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<MvcResult>> futures = new ArrayList<>();
            for (Callable<MvcResult> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return call.call();
                }));
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
            List<MvcResult> results = new ArrayList<>();
            for (Future<MvcResult> future : futures) {
                results.add(future.get(30, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    /** 顶层信封恒为 {code,message,data} 三字段;结构化补充只允许藏在 data 里。 */
    private void assertThreeFieldEnvelope(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(root.size()).isEqualTo(3);
        assertThat(root.has("code")).isTrue();
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("data")).isTrue();
    }

    private long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private String todayPrefix() {
        return "PO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "%";
    }
}
