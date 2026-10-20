package com.demo.supplyagent.web;

import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W1-D4 补货建议契约测试。夹具已对库核验（最新快照 2026-09-03）：
 * AUD-001 库存18，近7日=15(日均2.14)/30日=22(0.73)；CHG-001 库存25，30日=11(0.37)；
 * ACC-001 库存208，近7日=2(0.29)；AUD-002/3/4 库存 242/53/84。
 * 使用商品安全库存和提前期，目标保护期不重复叠加提前期，建议量按商品箱规向上取整。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
// W2-D1:本类职责是 Controller 行为契约,认证语义由 security.AuthFlowIntegrationTest 验证,故只装配认证上下文
@WithMockUser
class SuggestReplenishControllerTest {

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

    private ResultActions postJson(Map<String, ?> body) throws Exception {
        return mvc.perform(post("/api/agent/tools/suggest-replenish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postJson(String rawBody) throws Exception {
        return mvc.perform(post("/api/agent/tools/suggest-replenish")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody));
    }

    @Test
    void skusModeSuggestUrgentWithCeiling() throws Exception {
        // ceil(2.14×14+60)=90；缺口72，箱规24，建议72。
        String name = jdbc.queryForObject(
                "SELECT name FROM product WHERE sku_code = 'AUD-001'", String.class);
        postJson(Map.of("skus", List.of("AUD-001"), "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].productName").value(name))
                .andExpect(jsonPath("$.data.items[0].category").value("audio"))
                .andExpect(jsonPath("$.data.items[0].currentQty").value(18))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(2.14))
                .andExpect(jsonPath("$.data.items[0].targetCoverDays").value(14))
                .andExpect(jsonPath("$.data.items[0].safetyQty").value(60))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(72))
                .andExpect(jsonPath("$.data.items[0].urgency").value("urgent"))
                .andExpect(jsonPath("$.data.items[0].reason").value(containsString("日均2.14")))
                .andExpect(jsonPath("$.data.items[0].reason").value(containsString("目标覆盖14天")))
                .andExpect(jsonPath("$.data.items[0].reason").value(containsString("建议补72")));
    }

    @Test
    void productSafetyMakesChargingSkuUrgent() throws Exception {
        // CHG-001: ceil(0.37×14+45)=51，缺口26按箱规24取整到48；25低于45+提前期需求。
        postJson(Map.of("skus", List.of("CHG-001"), "days", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].currentQty").value(25))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.37))
                .andExpect(jsonPath("$.data.items[0].targetCoverDays").value(14))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(48))
                .andExpect(jsonPath("$.data.items[0].urgency").value("urgent"));
    }

    @Test
    void stockSufficientSuggestionZeroAndNone() throws Exception {
        // ACC-001 库存208 远超目标 → 0；0 > 0 判据不成立 → none。
        postJson(Map.of("skus", List.of("ACC-001"), "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].currentQty").value(208))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.29))
                .andExpect(jsonPath("$.data.items[0].targetCoverDays").value(14))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(0))
                .andExpect(jsonPath("$.data.items[0].urgency").value("none"));
    }

    @Test
    void explicitTargetCoverDaysOverridesDefault() throws Exception {
        // 7天总覆盖已含7天提前期；目标75，缺口57，整箱72，与14天同为3箱但计算依据不同。
        postJson(Map.of("skus", List.of("AUD-001"), "days", 7, "targetCoverDays", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].targetCoverDays").value(7))
                .andExpect(jsonPath("$.data.items[0].targetQty").value(75))
                .andExpect(jsonPath("$.data.items[0].protectionDays").value(7))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(72))
                .andExpect(jsonPath("$.data.items[0].urgency").value("urgent"));
    }

    @Test
    void categoryModeDaysThirtySuggestions() throws Exception {
        // audio 类目：AUD-001目标71、缺口53、箱规24向上为72；其余库存充足。
        postJson(Map.of("category", "audio", "days", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].currentQty").value(18))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.73))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(72))
                .andExpect(jsonPath("$.data.items[0].urgency").value("urgent"))
                .andExpect(jsonPath("$.data.items[1].sku").value("AUD-002"))
                .andExpect(jsonPath("$.data.items[1].suggestedQty").value(0))
                .andExpect(jsonPath("$.data.items[1].urgency").value("none"))
                .andExpect(jsonPath("$.data.items[2].sku").value("AUD-003"))
                .andExpect(jsonPath("$.data.items[2].suggestedQty").value(0))
                .andExpect(jsonPath("$.data.items[3].sku").value("AUD-004"))
                .andExpect(jsonPath("$.data.items[3].suggestedQty").value(0));
    }

    @Test
    void unknownSkuGoesToMissingNotFailure() throws Exception {
        postJson(Map.of("skus", List.of("NOPE-999"), "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(1)))
                .andExpect(jsonPath("$.data.missingSkus[0]").value("NOPE-999"));
    }

    @Test
    void outOfStockSuggestsRefillWithOutUrgency() throws Exception {
        // 合成 09-04 快照行 qty=0（晚于种子最新 09-03）：AUD-004 库存变 0；
        // 目标21件，箱规10建议30；库存日期晚于销量截止日必须人工复核。
        jdbc.execute("""
                INSERT INTO inventory_snapshot (sku_code, snap_date, stock_qty, created_at, updated_at) VALUES
                ('AUD-004', '2026-09-04', 0, '2026-09-04 09:00:00.000', '2026-09-04 09:00:00.000')
                """);
        postJson(Map.of("skus", List.of("AUD-004"), "days", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].currentQty").value(0))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.03))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(30))
                .andExpect(jsonPath("$.data.items[0].assessmentStatus").value("review_required"))
                .andExpect(jsonPath("$.data.items[0].urgency").value("out"));
    }

    @Test
    void changedProductPolicyIsReadFromDatabase() throws Exception {
        jdbc.update("UPDATE product SET safety_stock=5, lead_days=21, pkg_qty=10 WHERE sku_code='AUD-001'");
        // max(7,21) coverage: ceil(2.14*21+5)=50, gap32, pack10 =>40.
        postJson(Map.of("skus", List.of("AUD-001"), "days", 7, "targetCoverDays", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].safetyQty").value(5))
                .andExpect(jsonPath("$.data.items[0].leadDays").value(21))
                .andExpect(jsonPath("$.data.items[0].pkgQty").value(10))
                .andExpect(jsonPath("$.data.items[0].targetQty").value(50))
                .andExpect(jsonPath("$.data.items[0].rawGap").value(32))
                .andExpect(jsonPath("$.data.items[0].suggestedQty").value(40))
                .andExpect(jsonPath("$.data.items[0].assessmentStatus").value("review_required"))
                .andExpect(jsonPath("$.data.items[0].stockSnapDate").value("2026-09-03"))
                .andExpect(jsonPath("$.data.items[0].salesWindowEnd").value("2026-09-03"));
    }

    @Test
    void offShelfProductIsFlaggedForReview() throws Exception {
        postJson(Map.of("skus", List.of("ACC-003"), "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].shelfStatus").value("off_shelf"))
                .andExpect(jsonPath("$.data.items[0].assessmentStatus").value("review_required"))
                .andExpect(jsonPath("$.data.items[0].reason").value(containsString("不可创建采购草稿")));
    }

    @Test
    void skusAndCategoryTogetherRejected() throws Exception {
        postJson(Map.of("skus", List.of("AUD-001"), "category", "audio", "days", 7))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 与 category 只能二选一"));
    }

    @Test
    void neitherSkusNorCategoryRejected() throws Exception {
        postJson(Map.of("days", 7))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 与 category 不能同时为空"));
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void emptyBodyRejected() throws Exception {
        mvc.perform(post("/api/agent/tools/suggest-replenish")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体不能为空"));
    }

    @Test
    void daysOtherThanSevenOrThirtyRejected() throws Exception {
        postJson(Map.of("skus", List.of("AUD-001"), "days", 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("days 必须为 7 或 30"));
        postJson(Map.of("skus", List.of("AUD-001")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void targetCoverDaysNotAllowedRejected() throws Exception {
        postJson(Map.of("skus", List.of("AUD-001"), "days", 7, "targetCoverDays", 10))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("targetCoverDays 必须为 7、14 或 30"));
    }

    @Test
    void moreThanTwentySkusRejected() throws Exception {
        List<String> manySkus = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "SKU-" + i)
                .collect(Collectors.toList());
        postJson(Map.of("skus", manySkus, "days", 7))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 数量不能超过 20"));
    }

    @Test
    void malformedJsonBodyRejectedAs400() throws Exception {
        mvc.perform(post("/api/agent/tools/suggest-replenish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }
}
