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
import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W1-D3 销量查询垂直切片契约测试（基准日 = seed 固定 2026-09-03）。
 * 夹具（已对 sale_order 聚合核验）：AUD-001 近7天=15/近30天=22；AUD-002=2/7；
 * AUD-003=1/6；AUD-004=0/1。日均 = total/days HALF_UP 2 位。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
// W2-D1:本类职责是 Controller 行为契约,认证语义由 security.AuthFlowIntegrationTest 验证,故只装配认证上下文
@WithMockUser
class QuerySalesControllerTest {

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
        return mvc.perform(post("/api/agent/tools/query-sales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postJson(String rawBody) throws Exception {
        return mvc.perform(post("/api/agent/tools/query-sales")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody));
    }

    @Test
    void singleSkuSevenDaysQuery() throws Exception {
        String name = jdbc.queryForObject(
                "SELECT name FROM product WHERE sku_code = 'AUD-001'", String.class);
        postJson(Map.of("sku", "AUD-001", "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].productName").value(name))
                .andExpect(jsonPath("$.data.items[0].category").value("audio"))
                .andExpect(jsonPath("$.data.items[0].days").value(7))
                .andExpect(jsonPath("$.data.items[0].totalQty").value(15))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(2.14))
                .andExpect(jsonPath("$.data.items[0].series", hasSize(7)))
                .andExpect(jsonPath("$.data.items[0].series[0].date").value("2026-08-28"))
                .andExpect(jsonPath("$.data.items[0].series[6].date").value("2026-09-03"))
                .andExpect(jsonPath("$.data.items[0].series[3].date").value("2026-08-31"))
                .andExpect(jsonPath("$.data.items[0].series[3].qty").value(0))
                .andExpect(jsonPath("$.data.items[0].series[4].qty").value(3))
                .andExpect(jsonPath("$.data.items[0].series[6].qty").value(3));
    }

    @Test
    void singleSkuThirtyDaysQuery() throws Exception {
        postJson(Map.of("sku", "AUD-001", "days", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].totalQty").value(22))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.73))
                .andExpect(jsonPath("$.data.items[0].days").value(30))
                .andExpect(jsonPath("$.data.items[0].series", hasSize(30)))
                .andExpect(jsonPath("$.data.items[0].series[0].date").value("2026-08-05"))
                .andExpect(jsonPath("$.data.items[0].series[0].qty").value(0))
                .andExpect(jsonPath("$.data.items[0].series[29].date").value("2026-09-03"));
    }

    @Test
    void categorySevenDaysQuery() throws Exception {
        postJson(Map.of("category", "audio", "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].totalQty").value(15))
                .andExpect(jsonPath("$.data.items[1].sku").value("AUD-002"))
                .andExpect(jsonPath("$.data.items[1].totalQty").value(2))
                .andExpect(jsonPath("$.data.items[1].avgDailyQty").value(0.29))
                .andExpect(jsonPath("$.data.items[2].sku").value("AUD-003"))
                .andExpect(jsonPath("$.data.items[2].totalQty").value(1))
                .andExpect(jsonPath("$.data.items[3].sku").value("AUD-004"))
                .andExpect(jsonPath("$.data.items[3].totalQty").value(0))
                .andExpect(jsonPath("$.data.items[3].avgDailyQty").value(0.0))
                .andExpect(jsonPath("$.data.items[3].series", hasSize(7)))
                .andExpect(jsonPath("$.data.items[3].series[0].date").value("2026-08-28"));
    }

    @Test
    void categoryThirtyDaysQuery() throws Exception {
        postJson(Map.of("category", "audio", "days", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].totalQty").value(22))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.73))
                .andExpect(jsonPath("$.data.items[1].sku").value("AUD-002"))
                .andExpect(jsonPath("$.data.items[1].totalQty").value(7))
                .andExpect(jsonPath("$.data.items[1].avgDailyQty").value(0.23))
                .andExpect(jsonPath("$.data.items[2].sku").value("AUD-003"))
                .andExpect(jsonPath("$.data.items[2].totalQty").value(6))
                .andExpect(jsonPath("$.data.items[2].avgDailyQty").value(0.2))
                .andExpect(jsonPath("$.data.items[3].sku").value("AUD-004"))
                .andExpect(jsonPath("$.data.items[3].totalQty").value(1))
                .andExpect(jsonPath("$.data.items[3].avgDailyQty").value(0.03))
                .andExpect(jsonPath("$.data.items[3].series", hasSize(30)))
                .andExpect(jsonPath("$.data.items[3].series[29].date").value("2026-09-03"));
    }

    @Test
    void unknownSkuGoesToMissingNotFailure() throws Exception {
        postJson(Map.of("sku", "NOPE-999", "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(1)))
                .andExpect(jsonPath("$.data.missingSkus[0]").value("NOPE-999"));
    }

    @Test
    void skuAndCategoryTogetherRejected() throws Exception {
        postJson(Map.of("sku", "AUD-001", "category", "audio", "days", 7))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("sku 与 category 只能二选一"));
    }

    @Test
    void neitherSkuNorCategoryRejected() throws Exception {
        postJson(Map.of("days", 7))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("sku 与 category 不能同时为空"));
        postJson("{\"sku\":\"\",\"category\":null,\"days\":7}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void daysOtherThanSevenOrThirtyRejected() throws Exception {
        postJson(Map.of("sku", "AUD-001", "days", 5))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("days 必须为 7 或 30"));
        postJson(Map.of("sku", "AUD-001"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("days 必须为 7 或 30"));
        postJson(Map.of("sku", "AUD-001", "days", 365))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void skuWithoutSalesFillsZeroSeries() throws Exception {
        // AUD-004 在 08-28~09-03 无任何订单（种子 30 天内仅 08-13 一笔）。
        postJson(Map.of("sku", "AUD-004", "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-004"))
                .andExpect(jsonPath("$.data.items[0].totalQty").value(0))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.0))
                .andExpect(jsonPath("$.data.items[0].series", hasSize(7)))
                .andExpect(jsonPath("$.data.items[0].series[0].date").value("2026-08-28"))
                .andExpect(jsonPath("$.data.items[0].series[0].qty").value(0))
                .andExpect(jsonPath("$.data.items[0].series[6].qty").value(0));
    }

    @Test
    void avgDailyQtyRoundingHalfUpTwoDigits() throws Exception {
        // 2/7=0.2857... -> 0.29（进位）；15/7=2.1428... -> 2.14（舍去）；6/30 -> 0.20 补零。
        postJson(Map.of("sku", "AUD-002", "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].totalQty").value(2))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.29));
        postJson(Map.of("sku", "AUD-003", "days", 30))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].totalQty").value(6))
                .andExpect(jsonPath("$.data.items[0].avgDailyQty").value(0.2));
    }

    @Test
    void unknownCategoryReturnsEmptyItems() throws Exception {
        postJson(Map.of("category", "food", "days", 7))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0))
                .andExpect(jsonPath("$.data.items", hasSize(0)))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)));
    }

    @Test
    void malformedJsonBodyRejectedAs400() throws Exception {
        mvc.perform(post("/api/agent/tools/query-sales")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("请求体格式错误"));
    }
}
