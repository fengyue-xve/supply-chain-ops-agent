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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W3-③ create-purchase-order 集成测试(真实 SecurityFilterChain + 真实 PoWriteService + 真实 test 库)。
 * 覆盖:成功落 pending 草稿(采购单+确认流+审计+幂等登记四表)、按 sku 排序编号、
 * 同键同参回放(含逆序输入,零写入)、同键异参 409、缺/非法幂等键 400、结构化 400 errors[] 零残留、
 * 请求体形状校验、溯源头空值/超长 400、幂等作用域按 (工具,操作者,键) 隔离(跨 admin 不串）、库中无凭证。
 * 库中只产生 pending:任何成功响应都不允许出现 confirmed。
 * 清理:精确删本次捕获的 po_no/幂等键 + 第二 admin 夹具;另按「今天业务日」前缀自愈清扫
 * (种子全部为历史日期,不可能误伤),SchemaSeedSmokeTest 的行数断言不被本类污染。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CreatePurchaseOrderIntegrationTest {

    private static final String CREATE_URI = "/api/agent/tools/create-purchase-order";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String KEY_HEADER = "Idempotency-Key";
    /** 第二个 admin 夹具(验证幂等作用域按操作者隔离);测试库种子只有 admin/viewer。 */
    private static final String SECOND_ADMIN_USERNAME = "it-w3c-second-admin";
    private static final String SECOND_ADMIN_PASSWORD = "it-w3c-second-pass";

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PasswordEncoder passwordEncoder;

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
        for (String key : createdKeys) {
            jdbc.update("DELETE FROM idempotency_record WHERE idempotency_key = ?", key);
            jdbc.update("DELETE FROM po_confirmation WHERE idempotency_key = ?", key);
        }
        createdKeys.clear();
        jdbc.update("DELETE FROM app_user WHERE username = ?", SECOND_ADMIN_USERNAME);
        String todayPrefix = todayPrefix();
        jdbc.update("DELETE FROM agent_history WHERE task_key LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_confirmation WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM purchase_po WHERE po_no LIKE ?", todayPrefix);
        jdbc.update("DELETE FROM po_no_seq WHERE seq_date = ?", LocalDate.now());
        jdbc.update("DELETE FROM product WHERE sku_code IN ('TST-PO-AUD-001','TST-PO-CHG-001')");
    }

    private String todayPrefix() {
        return "PO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "%";
    }

    // ---------- 成功路径:四表落库 + 恒 pending + 审计结构化列 ----------

    @Test
    void createPersistsPendingDraftRegistryConfirmationAndAudit() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-1");

        MvcResult result = mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48))
                        .header("X-Request-Id", "it-w3c-cpo-rid-1")
                        .header("X-Conversation-Id", "it-w3c-cpo-conv-1")
                        .header("X-Tool-Call-Id", "it-w3c-cpo-tc-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].sku").value("TST-PO-AUD-001"))
                .andExpect(jsonPath("$.data.items[0].qty").value(48))
                .andExpect(jsonPath("$.data.items[0].status").value("pending"))
                .andExpect(jsonPath("$.data.items[0].poNo").value(org.hamcrest.Matchers.matchesPattern("PO\\d{11}")))
                .andReturn();
        String poNo = data(result).at("/items/0/poNo").asText();

        Map<String, Object> po = jdbc.queryForMap(
                "SELECT sku_code, qty, status, manual_confirm_required FROM purchase_po WHERE po_no = ?", poNo);
        assertThat(po)
                .containsEntry("sku_code", "TST-PO-AUD-001")
                .containsEntry("qty", 48)
                .containsEntry("status", "pending")
                // TINYINT(1) 经 Connector/J 默认 tinyInt1isBit 映射为 Boolean
                .containsEntry("manual_confirm_required", true);

        Map<String, Object> conf = jdbc.queryForMap(
                "SELECT status, idempotency_key, operator, request_id, conversation_id, tool_call_id "
                        + "FROM po_confirmation WHERE po_no = ?", poNo);
        assertThat(conf)
                .containsEntry("status", "pending_confirmation")
                .containsEntry("idempotency_key", key)
                .containsEntry("operator", "admin")
                .containsEntry("request_id", "it-w3c-cpo-rid-1")
                .containsEntry("conversation_id", "it-w3c-cpo-conv-1")
                .containsEntry("tool_call_id", "it-w3c-cpo-tc-1");

        Map<String, Object> audit = jdbc.queryForMap(
                "SELECT action_type, who, operator, before_json, after_json, request_id, conversation_id, tool_call_id "
                        + "FROM agent_history WHERE task_key = ?", poNo);
        assertThat(audit)
                .containsEntry("action_type", "po_create")
                .containsEntry("who", "admin")
                .containsEntry("operator", "admin")
                .containsEntry("before_json", null)
                .containsEntry("after_json", "{\"status\":\"pending\"}")
                .containsEntry("request_id", "it-w3c-cpo-rid-1")
                .containsEntry("conversation_id", "it-w3c-cpo-conv-1")
                .containsEntry("tool_call_id", "it-w3c-cpo-tc-1");

        Map<String, Object> registry = jdbc.queryForMap(
                "SELECT tool_name, request_digest, response_json, operator, request_id "
                        + "FROM idempotency_record WHERE idempotency_key = ?", key);
        assertThat(registry)
                .containsEntry("tool_name", "create-purchase-order")
                .containsEntry("operator", "admin")
                .containsEntry("request_id", "it-w3c-cpo-rid-1");
        assertThat((String) registry.get("request_digest")).matches("[0-9a-f]{64}");
        assertThat((String) registry.get("response_json")).contains(poNo);
    }

    // ---------- 多 SKU:按 sku 排序编号,输入顺序无关 ----------

    @Test
    void multiItemCreateNumbersPoNosBySkuOrder() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-multi");

        MvcResult result = mvc.perform(createRequest(token, key, items("TST-PO-CHG-001", 24, "TST-PO-AUD-001", 48)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[0].sku").value("TST-PO-AUD-001"))
                .andExpect(jsonPath("$.data.items[1].sku").value("TST-PO-CHG-001"))
                .andReturn();
        JsonNode items = data(result).path("items");
        String firstPoNo = items.at("/0/poNo").asText();
        String secondPoNo = items.at("/1/poNo").asText();
        assertThat(firstPoNo).matches("PO\\d{11}");
        assertThat(secondPoNo).matches("PO\\d{11}");
        // 同一次请求分配连续号段,按 sku 排序依次编号
        long firstSeq = Long.parseLong(firstPoNo.substring(3 + 8));
        long secondSeq = Long.parseLong(secondPoNo.substring(3 + 8));
        assertThat(secondSeq).isEqualTo(firstSeq + 1);
        assertThat(firstPoNo.substring(0, 11)).isEqualTo(secondPoNo.substring(0, 11));
    }

    // ---------- 幂等回放:同键同参(逆序输入)零写入 ----------

    @Test
    void replayedWithReversedItemOrderReturnsSameDraftsWithoutNewWrites() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-replay");

        MvcResult first = mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48, "TST-PO-CHG-001", 24)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andReturn();
        JsonNode firstItems = data(first).path("items");

        MvcResult replay = mvc.perform(createRequest(token, key, items("TST-PO-CHG-001", 24, "TST-PO-AUD-001", 48)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(true))
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn();
        // 回放内容与首响完全一致(poNo 集合相同,顺序同为规范化 sku 序)
        assertThat(data(replay).path("items")).isEqualTo(firstItems);

        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation WHERE idempotency_key = ?", key)).isEqualTo(2);
        for (JsonNode item : firstItems) {
            String poNo = item.path("poNo").asText();
            assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE task_key = ?", poNo)).isEqualTo(1);
        }
    }

    // ---------- 同键异参:409 ----------

    @Test
    void sameKeyDifferentParamsIs409WithKeyEchoed() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-conflict");

        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48)))
                .andExpect(status().isOk());

        MvcResult conflict = mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 96)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.message").value("该幂等键已用于不同参数的请求，拒绝执行"))
                .andExpect(jsonPath("$.data.idempotencyKey").value(key))
                .andReturn();
        assertThreeFieldEnvelope(conflict);

        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation WHERE idempotency_key = ?", key)).isEqualTo(1);
    }

    // ---------- 幂等作用域:(工具,操作者,键)——跨 admin 隔离 ----------

    @Test
    void sameKeyAcrossDifferentOperatorsIsIndependent() throws Exception {
        String adminA = adminToken();
        String adminB = secondAdminToken();
        String key = register("it-w3c-cpo-cross-op");
        String body = items("TST-PO-AUD-001", 48);

        MvcResult firstByA = mvc.perform(createRequest(adminA, key, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andReturn();
        MvcResult firstByB = mvc.perform(createRequest(adminB, key, items("TST-PO-CHG-001", 24)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andReturn();

        String poNoA = data(firstByA).at("/items/0/poNo").asText();
        String poNoB = data(firstByB).at("/items/0/poNo").asText();
        assertThat(poNoB).isNotEqualTo(poNoA);
        // 同一 key 在两个操作者名下各留一行登记、各自建单，互不占用
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isEqualTo(2);
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE po_no IN (?, ?)", poNoA, poNoB)).isEqualTo(2);
    }

    @Test
    void replayNeverServesAnotherOperatorsResult() throws Exception {
        String adminA = adminToken();
        String adminB = secondAdminToken();
        String key = register("it-w3c-cpo-cross-replay");
        String body = items("TST-PO-AUD-001", 48);

        String poNoA = data(mvc.perform(createRequest(adminA, key, body))
                .andExpect(status().isOk()).andReturn()).at("/items/0/poNo").asText();

        // B 用同 key 的另一 SKU:不得回放 A 的历史结果，幂等作用域独立
        MvcResult firstByB = mvc.perform(createRequest(adminB, key, items("TST-PO-CHG-001", 24)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(false))
                .andReturn();
        String poNoB = data(firstByB).at("/items/0/poNo").asText();
        assertThat(poNoB).isNotEqualTo(poNoA);

        // 重复请求各自回放各自的结果:B 拿到 B 的、A 仍拿到 A 的
        MvcResult replayByB = mvc.perform(createRequest(adminB, key, items("TST-PO-CHG-001", 24)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(true))
                .andReturn();
        assertThat(data(replayByB).at("/items/0/poNo").asText()).isEqualTo(poNoB);
        MvcResult replayByA = mvc.perform(createRequest(adminA, key, body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicated").value(true))
                .andReturn();
        assertThat(data(replayByA).at("/items/0/poNo").asText()).isEqualTo(poNoA);
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isEqualTo(2);
    }

    @Test
    void differentKeySameSkuReturnsExistingIntentWithoutNewWrites() throws Exception {
        String token=adminToken(),first=register("it-w3c-cpo-existing0"),second=register("it-w3c-cpo-existing1");
        String poNo=data(mvc.perform(createRequest(token,first,items("TST-PO-AUD-001",48)))
                .andExpect(status().isOk()).andReturn()).at("/items/0/poNo").asText();
        mvc.perform(createRequest(token,second,items("TST-PO-AUD-001",72)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.error").value("pending_purchase_exists"))
                .andExpect(jsonPath("$.data.existingPoNo").value(poNo))
                .andExpect(jsonPath("$.data.existingOrders[0].poNo").value(poNo))
                .andExpect(jsonPath("$.data.existingOrders[0].qty").value(48));
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key=?",second)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE sku_code='TST-PO-AUD-001'")).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE task_key=?",poNo)).isEqualTo(1);
    }

    @Test
    void anotherOperatorCannotReplayOrDuplicateExistingPendingIntent() throws Exception {
        String first=adminToken(),second=secondAdminToken(),key=register("it-w3c-cpo-op-pending");
        String poNo=data(mvc.perform(createRequest(first,key,items("TST-PO-AUD-001",48)))
                .andExpect(status().isOk()).andReturn()).at("/items/0/poNo").asText();
        mvc.perform(createRequest(second,key,items("TST-PO-AUD-001",48)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.existingPoNo").value(poNo));
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key=?",key)).isEqualTo(1);
    }

    @Test
    void batchWithOneExistingIntentWritesNothingForOtherItems() throws Exception {
        String token=adminToken(),key=register("it-w3c-cpo-batch-existing");
        long beforePo=countRows("SELECT COUNT(*) FROM purchase_po");
        long beforeHistory=countRows("SELECT COUNT(*) FROM agent_history");
        // AUD-001 的历史种子 pending 必须保留，另一 SKU 的新意向也不能部分写入。
        mvc.perform(createRequest(token,key,items("AUD-001",48,"TST-PO-CHG-001",24)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.data.existingPoNo").value("PO20260902001"));
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po")).isEqualTo(beforePo);
        assertThat(countRows("SELECT COUNT(*) FROM agent_history")).isEqualTo(beforeHistory);
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation WHERE idempotency_key=?",key)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key=?",key)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM po_no_seq WHERE seq_date=?",LocalDate.now())).isZero();
    }

    @Test
    void replayPrecedesProductChangesAndSameKeyDifferentInvalidParamsIs409() throws Exception {
        String token=adminToken(),key=register("it-w3c-cpo-replay-changed");
        String poNo=data(mvc.perform(createRequest(token,key,items("TST-PO-AUD-001",48)))
                .andExpect(status().isOk()).andReturn()).at("/items/0/poNo").asText();
        jdbc.update("UPDATE product SET shelf_status='off_shelf',pkg_qty=25 WHERE sku_code='TST-PO-AUD-001'");
        mvc.perform(createRequest(token,key,items("TST-PO-AUD-001",48)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicated").value(true))
                .andExpect(jsonPath("$.data.items[0].poNo").value(poNo));
        mvc.perform(createRequest(token,key,items("TST-PO-AUD-001",49)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.data.idempotencyKey").value(key));
    }

    @Test
    void quantityRoundingNeverOverflowsIntoNegativeSuggestion() throws Exception {
        String key=register("it-w3c-cpo-quantity-overflow");
        mvc.perform(createRequest(adminToken(),key,items("TST-PO-AUD-001",Integer.MAX_VALUE)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.data.errors[0].code").value("qty_out_of_range"))
                .andExpect(jsonPath("$.data.errors[0].suggestedQty").doesNotExist());
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key=?",key)).isZero();
    }

    @Test
    void confirmedIntentDoesNotPermanentlyPreventFuturePurchase() throws Exception {
        String token=adminToken(),first=register("it-w3c-cpo-confirmed0"),second=register("it-w3c-cpo-confirmed1");
        String poNo=data(mvc.perform(createRequest(token,first,items("TST-PO-AUD-001",48)))
                .andExpect(status().isOk()).andReturn()).at("/items/0/poNo").asText();
        mvc.perform(post("/api/agent/tools/confirm-po").header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
                .contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(Map.of("poNo",poNo))))
                .andExpect(status().isOk());
        mvc.perform(createRequest(token,second,items("TST-PO-AUD-001",48)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.duplicated").value(false));
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE sku_code='TST-PO-AUD-001' AND status='pending'"))
                .isEqualTo(1);
    }

    // ---------- 幂等键缺失/非法:400 且零写入 ----------

    @Test
    void missingIdempotencyKeyIs400WithoutWrites() throws Exception {
        long beforeRegistry = countRows("SELECT COUNT(*) FROM idempotency_record");
        long beforePo = countRows("SELECT COUNT(*) FROM purchase_po");

        mvc.perform(createRequest(adminToken(), null, items("TST-PO-AUD-001", 48)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("缺少 Idempotency-Key 请求头"));

        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record")).isEqualTo(beforeRegistry);
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po")).isEqualTo(beforePo);
    }

    @Test
    void malformedIdempotencyKeyIs400() throws Exception {
        String token = adminToken();
        mvc.perform(createRequest(token, "bad key!", items("TST-PO-AUD-001", 48)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key 仅允许字母、数字与 _ . : -"));
        mvc.perform(createRequest(token, "k".repeat(81), items("TST-PO-AUD-001", 48)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Idempotency-Key 不能超过 80 字符"));
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE po_no LIKE ?", todayPrefix())).isZero();
    }

    // ---------- 业务校验错误:硬 400 + 结构化 errors[] + 零残留 ----------

    @Test
    void validationErrorsCarryStructuredDetailsAndLeaveZeroResidue() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-invalid");
        String body = objectMapper.writeValueAsString(Map.of("items", List.of(
                Map.of("sku", "ACC-003", "qty", 30),   // 已下架
                Map.of("sku", "TST-PO-AUD-001", "qty", 25),   // 非包装整数倍(pkg 24)
                Map.of("sku", "NOPE-001", "qty", 1),   // 不存在
                Map.of("sku", "AUD-002", "qty", 0)))); // 非正数

        MvcResult result = mvc.perform(createRequest(token, key, body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("参数校验未通过"))
                .andReturn();
        // 结构化 errors[] 只藏在 data 内,顶层仍是 {code,message,data}
        assertThreeFieldEnvelope(result);

        JsonNode errors = data(result).path("errors");
        assertThat(errors).hasSize(4);
        Set<String> codes = new HashSet<>();
        errors.forEach(e -> codes.add(e.path("code").asText()));
        assertThat(codes).containsExactlyInAnyOrder(
                "sku_off_shelf", "qty_not_pkg_multiple", "sku_not_found", "invalid_qty");

        JsonNode pkgError = findByCode(errors, "qty_not_pkg_multiple");
        assertThat(pkgError.path("qty").asInt()).isEqualTo(25);
        assertThat(pkgError.path("pkgQty").asInt()).isEqualTo(24);
        assertThat(pkgError.path("suggestedQty").asInt()).isEqualTo(48);
        // 只有包装倍数错携带 qty/pkgQty/suggestedQty 补充字段
        assertThat(findByCode(errors, "sku_off_shelf").has("suggestedQty")).isFalse();
        assertThat(findByCode(errors, "invalid_qty").has("pkgQty")).isFalse();

        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation WHERE idempotency_key = ?", key)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE request_id LIKE 'it-w3c-cpo-%'")).isZero();
    }

    // ---------- 请求体形状:400 且不触库 ----------

    @Test
    void itemShapeViolationsAre400() throws Exception {
        String token = adminToken();
        long beforePo = countRows("SELECT COUNT(*) FROM purchase_po");

        mvc.perform(createRequest(token, register("it-w3c-cpo-shape-1"), "{\"items\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("items 不能为空"));

        List<Map<String, Object>> tooMany = new ArrayList<>();
        for (int i = 1; i <= 21; i++) {
            tooMany.add(Map.of("sku", String.format("S%02d", i), "qty", 1));
        }
        mvc.perform(createRequest(token, register("it-w3c-cpo-shape-2"),
                        objectMapper.writeValueAsString(Map.of("items", tooMany))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("items 不能超过 20 条"));

        mvc.perform(createRequest(token, register("it-w3c-cpo-shape-3"), items("TST-PO-AUD-001", 48, "TST-PO-AUD-001", 48)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("items 中存在重复 SKU: TST-PO-AUD-001"));

        mvc.perform(createRequest(token, register("it-w3c-cpo-shape-4"), items("aud-001", 48)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(
                        "sku 格式非法（仅大写字母、数字与短横线，最长 32 位）: aud-001"));

        Map<String, Object> nullQty = new HashMap<>();
        nullQty.put("sku", "TST-PO-AUD-001");
        nullQty.put("qty", null);
        mvc.perform(createRequest(token, register("it-w3c-cpo-shape-5"),
                        objectMapper.writeValueAsString(Map.of("items", List.of(nullQty)))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("qty 不能为空: TST-PO-AUD-001"));

        assertThat(countRows("SELECT COUNT(*) FROM purchase_po")).isEqualTo(beforePo);
    }

    // ---------- 溯源头超长:400 ----------

    @Test
    void oversizedTraceHeadersAre400() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-headers");
        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48))
                        .header("X-Request-Id", "r".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Request-Id 不能超过 64 字符"));
        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48))
                        .header("X-Conversation-Id", "c".repeat(81)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Conversation-Id 不能超过 80 字符"));
        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48))
                        .header("X-Tool-Call-Id", "t".repeat(65)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Tool-Call-Id 不能超过 64 字符"));
    }

    // ---------- 溯源头空值:400(不静默当缺省),零写入 ----------

    @Test
    void blankTraceHeadersAre400WithoutWrites() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-blank");
        long beforePo = countRows("SELECT COUNT(*) FROM purchase_po");
        long beforeRegistry = countRows("SELECT COUNT(*) FROM idempotency_record");

        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48)).header("X-Request-Id", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Request-Id 不能为空"));
        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48)).header("X-Conversation-Id", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Conversation-Id 不能为空"));
        mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48)).header("X-Tool-Call-Id", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("X-Tool-Call-Id 不能为空"));
        // 显式空 Idempotency-Key(createRequest 传 null 不带键,再追加空值头):按缺失处理
        mvc.perform(createRequest(token, null, items("TST-PO-AUD-001", 48)).header(KEY_HEADER, " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("缺少 Idempotency-Key 请求头"));

        assertThat(countRows("SELECT COUNT(*) FROM purchase_po")).isEqualTo(beforePo);
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record")).isEqualTo(beforeRegistry);
    }

    // ---------- 落库内容不含凭证 ----------

    @Test
    void persistedRowsNeverContainCredentials() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cpo-secrets");

        MvcResult result = mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48)))
                .andExpect(status().isOk())
                .andReturn();
        String poNo = data(result).at("/items/0/poNo").asText();

        StringBuilder dump = new StringBuilder();
        dump.append(jdbc.queryForList("SELECT * FROM purchase_po WHERE po_no = ?", poNo));
        dump.append(jdbc.queryForList("SELECT * FROM po_confirmation WHERE po_no = ?", poNo));
        dump.append(jdbc.queryForList("SELECT * FROM agent_history WHERE task_key = ?", poNo));
        dump.append(jdbc.queryForList("SELECT * FROM idempotency_record WHERE idempotency_key = ?", key));
        assertThat(dump.toString())
                .doesNotContain("Bearer").doesNotContain(token).doesNotContain("eyJ");
    }

    // ---------- helpers ----------

    private String adminToken() throws Exception {
        return token("admin", "admin123");
    }

    /** 第二 admin 夹具:直接落库(bcrypt 哈希)+真实登录换取 JWT;行由 @AfterEach 清理。 */
    private String secondAdminToken() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT IGNORE INTO app_user (username, password_hash, role, created_at, updated_at) "
                        + "VALUES (?, ?, 'admin', ?, ?)",
                SECOND_ADMIN_USERNAME, passwordEncoder.encode(SECOND_ADMIN_PASSWORD), now, now);
        return token(SECOND_ADMIN_USERNAME, SECOND_ADMIN_PASSWORD);
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

    /** 顶层信封恒为 {code,message,data} 三字段;结构化补充只允许藏在 data 里。 */
    private void assertThreeFieldEnvelope(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(root.size()).isEqualTo(3);
        assertThat(root.has("code")).isTrue();
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("data")).isTrue();
    }

    private MockHttpServletRequestBuilder createRequest(String token, String key, String body) {
        MockHttpServletRequestBuilder request =
                post(CREATE_URI).contentType(MediaType.APPLICATION_JSON).content(body);
        if (token != null) {
            request = request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        }
        if (key != null) {
            request = request.header(KEY_HEADER, key);
        }
        return request;
    }

    /** items(...) 以 (sku, qty) 对展开;顺序即输入顺序(测试逆序输入用)。 */
    private String items(Object... skuQtyPairs) throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int i = 0; i < skuQtyPairs.length; i += 2) {
            items.add(Map.of("sku", skuQtyPairs[i], "qty", skuQtyPairs[i + 1]));
        }
        return objectMapper.writeValueAsString(Map.of("items", items));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    private JsonNode findByCode(JsonNode errors, String code) {
        for (JsonNode e : errors) {
            if (code.equals(e.path("code").asText())) {
                return e;
            }
        }
        throw new AssertionError("errors[] 中缺少 code=" + code);
    }

    private long countRows(String sql, Object... args) {
        Long count = jdbc.queryForObject(sql, Long.class, args);
        return count == null ? 0 : count;
    }

    private String register(String key) {
        createdKeys.add(key);
        return key;
    }
}
