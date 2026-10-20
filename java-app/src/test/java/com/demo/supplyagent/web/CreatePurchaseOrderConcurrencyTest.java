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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
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
 * W3-③ create-purchase-order 并发语义（真实链路 + 真实 test 库，MockMvc 多线程发起）：
 *  ① 同键同参并发 → 一方新建一方回放（duplicated=true），登记/业务/审计各恰一份；
 *  ② 同键异参并发 → 恰一方 200、另一方 409（摘要裁决）；
 *  ③ 不同键并发同 SKU → 仅一个 pending；不同 SKU 编号互不重复；
 *  ④ po_no 撞现存行（污染夹具）→ 整事务回滚 500、四表零残留、序号回退 → 修复后同键重试成功。
 * 断言不依赖并发到达顺序，只依赖互斥结果（一方胜出）。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CreatePurchaseOrderConcurrencyTest {

    private static final String CREATE_URI = "/api/agent/tools/create-purchase-order";
    private static final String LOGIN_URI = "/api/auth/login";
    private static final String KEY_HEADER = "Idempotency-Key";

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
        jdbc.update("DELETE FROM product WHERE sku_code IN ('TST-PO-AUD-001','TST-PO-CHG-001')");
    }

    // ---------- ① 同键同参并发:恰一次回放 ----------

    @Test
    void sameKeySameParamsConcurrentBecomesCreatePlusReplay() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cprace-same");
        String body = items("TST-PO-AUD-001", 48);

        List<MvcResult> results = runConcurrently(List.of(
                () -> mvc.perform(createRequest(token, key, body)).andReturn(),
                () -> mvc.perform(createRequest(token, key, body)).andReturn()));

        List<Integer> statuses = new ArrayList<>();
        List<Boolean> duplicatedFlags = new ArrayList<>();
        for (MvcResult result : results) {
            statuses.add(result.getResponse().getStatus());
            duplicatedFlags.add(data(result).path("duplicated").asBoolean());
        }
        assertThat(statuses).containsExactly(200, 200);
        assertThat(duplicatedFlags).containsExactlyInAnyOrder(false, true);
        String poNo = data(results.get(0)).at("/items/0/poNo").asText();
        assertThat(data(results.get(1)).at("/items/0/poNo").asText()).isEqualTo(poNo);

        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE po_no = ?", poNo)).isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE task_key = ?", poNo)).isEqualTo(1);
    }

    // ---------- ② 同键异参并发:恰一方成功 ----------

    @Test
    void sameKeyDifferentParamsConcurrentOneWinsOneConflicts() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cprace-conflict");

        List<MvcResult> results = runConcurrently(List.of(
                () -> mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48))).andReturn(),
                () -> mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 96))).andReturn()));

        List<Integer> statuses = new ArrayList<>();
        results.forEach(r -> statuses.add(r.getResponse().getStatus()));
        assertThat(statuses).containsExactlyInAnyOrder(200, 409);

        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation WHERE idempotency_key = ?", key))
                .isEqualTo(1);
    }

    // ---------- ③ 跨键并发同 SKU 只留一个未确认意向 ----------

    @Test
    void differentKeysConcurrentSameSkuCreateOnePendingIntent() throws Exception {
        String token = adminToken();
        List<String> keys = List.of(register("it-w3c-cprace-k0"), register("it-w3c-cprace-k1"),
                register("it-w3c-cprace-k2"), register("it-w3c-cprace-k3"));
        String body = items("TST-PO-AUD-001", 48);
        List<Callable<MvcResult>> calls = keys.stream()
                .map(key -> (Callable<MvcResult>) () -> mvc.perform(createRequest(token,key,body)).andReturn()).toList();
        List<MvcResult> results = runConcurrently(calls);
        assertThat(results.stream().map(r -> r.getResponse().getStatus()).toList())
                .containsExactlyInAnyOrder(200,409,409,409);
        String winner = results.stream().filter(r -> r.getResponse().getStatus()==200).findFirst().orElseThrow()
                .getResponse().getContentAsString();
        String poNo = objectMapper.readTree(winner).at("/data/items/0/poNo").asText();
        for (MvcResult result : results) {
            if (result.getResponse().getStatus()==409) {
                assertThat(data(result).path("existingPoNo").asText()).isEqualTo(poNo);
            }
        }
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE sku_code='TST-PO-AUD-001' AND status='pending'"))
                .isEqualTo(1);
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key LIKE 'it-w3c-cprace-k%'"))
                .isEqualTo(1);
    }

    @Test
    void differentSkusConcurrentAllocateDistinctPoNos() throws Exception {
        String token = adminToken();
        String firstKey=register("it-w3c-cprace-distinct0"),secondKey=register("it-w3c-cprace-distinct1");
        List<MvcResult> results=runConcurrently(List.of(
                () -> mvc.perform(createRequest(token,firstKey,items("TST-PO-AUD-001",48))).andReturn(),
                () -> mvc.perform(createRequest(token,secondKey,items("TST-PO-CHG-001",24))).andReturn()));
        Set<String> poNos=new HashSet<>();
        for(MvcResult result:results){
            assertThat(result.getResponse().getStatus()).isEqualTo(200);
            poNos.add(data(result).at("/items/0/poNo").asText());
        }
        assertThat(poNos).hasSize(2);
    }

    // ---------- ④ 撞现存 po_no:整事务回滚 → 修复夹具后同键重试成功 ----------

    @Test
    void poNoCollisionRollsBackWholeRequestThenRetrySucceeds() throws Exception {
        String token = adminToken();
        String key = register("it-w3c-cprace-collide");

        // 夹具:把今日计数器推到 500,并预占下一个号 PO<today>501(模拟历史遗留撞号)
        jdbc.update("DELETE FROM po_no_seq WHERE seq_date = ?", LocalDate.now());
        jdbc.update("INSERT INTO po_no_seq (seq_date, last_seq, created_at, updated_at) VALUES (?, 500, ?, ?)",
                LocalDate.now(), LocalDateTime.now(), LocalDateTime.now());
        String collidingPoNo = "PO" + LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE) + "501";
        jdbc.update("INSERT INTO purchase_po (po_no, sku_code, qty, status, manual_confirm_required, "
                        + "created_at, updated_at) VALUES (?, 'TST-PO-AUD-001', 1, 'confirmed', 1, ?, ?)",
                collidingPoNo, LocalDateTime.now(), LocalDateTime.now());

        MvcResult collision = mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48))
                        .header("X-Request-Id", "it-w3c-cprace-collide-rid"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("数据库访问异常，请稍后重试"))
                .andReturn();
        assertThreeFieldEnvelope(collision);

        // 零残留:登记、确认流、审计均无本次请求的行;序号回退到 500
        assertThat(countRows("SELECT COUNT(*) FROM idempotency_record WHERE idempotency_key = ?", key)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM po_confirmation WHERE idempotency_key = ?", key)).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM agent_history WHERE request_id = ?",
                "it-w3c-cprace-collide-rid")).isZero();
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE po_no = ?", collidingPoNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_seq FROM po_no_seq WHERE seq_date = ?",
                Integer.class, LocalDate.now())).isEqualTo(500);

        // 修复夹具(删除撞号行)后同键重试:成功且拿到原被占用的号
        jdbc.update("DELETE FROM purchase_po WHERE po_no = ?", collidingPoNo);
        MvcResult retried = mvc.perform(createRequest(token, key, items("TST-PO-AUD-001", 48)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(data(retried).at("/items/0/poNo").asText()).isEqualTo(collidingPoNo);
        assertThat(countRows("SELECT COUNT(*) FROM purchase_po WHERE po_no = ?", collidingPoNo)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_seq FROM po_no_seq WHERE seq_date = ?",
                Integer.class, LocalDate.now())).isEqualTo(501);
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

    private MockHttpServletRequestBuilder createRequest(String token, String key, String body) {
        return post(CREATE_URI)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .header(KEY_HEADER, key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    private String items(String sku, int qty) throws Exception {
        return objectMapper.writeValueAsString(Map.of("items", List.of(Map.of("sku", sku, "qty", qty))));
    }

    private JsonNode data(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
    }

    /** 顶层信封恒为 {code,message,data} 三字段;结构化补充只允许藏在 data 里。 */
    private void assertThreeFieldEnvelope(MvcResult result) throws Exception {
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertThat(root.size()).isEqualTo(3);
        assertThat(root.has("code")).isTrue();
        assertThat(root.has("message")).isTrue();
        assertThat(root.has("data")).isTrue();
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
