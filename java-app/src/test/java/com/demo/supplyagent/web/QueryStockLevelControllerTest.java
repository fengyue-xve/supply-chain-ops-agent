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

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W1-D2 库存查询垂直切片：8 项契约测试。
 * 种子事实：全部 SKU 最新快照日 = 2026-09-03；AUD-001=18(low)、CHG-001=25(ok)、
 * ACC-001=208(high)（公式 id=8）。out/边界档在测试内临时插入 09-04 行验证，@Transactional 回滚不留脏。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
@Transactional
// W2-D1:本类职责是 Controller 行为契约,认证语义由 security.AuthFlowIntegrationTest 验证,故只装配认证上下文
@WithMockUser
class QueryStockLevelControllerTest {

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
        return mvc.perform(post("/api/agent/tools/query-stock-level")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions postJson(String rawBody) throws Exception {
        return mvc.perform(post("/api/agent/tools/query-stock-level")
                .contentType(MediaType.APPLICATION_JSON)
                .content(rawBody));
    }

    @Test
    void singleSkuQueryReturnsLatestLevel() throws Exception {
        String name = jdbc.queryForObject(
                "SELECT name FROM product WHERE sku_code = 'AUD-001'", String.class);
        postJson(Map.of("skus", List.of("AUD-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].productName").value(name))
                .andExpect(jsonPath("$.data.items[0].category").value("audio"))
                .andExpect(jsonPath("$.data.items[0].availableQty").value(18))
                .andExpect(jsonPath("$.data.items[0].stockLevel").value("low"))
                .andExpect(jsonPath("$.data.items[0].snapDate").value("2026-09-03"))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)));
    }

    @Test
    void batchSkuQueryReturnsAllRequested() throws Exception {
        postJson(Map.of("skus", List.of("AUD-001", "CHG-001", "ACC-001")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[0].stockLevel").value("low"))
                .andExpect(jsonPath("$.data.items[1].sku").value("CHG-001"))
                .andExpect(jsonPath("$.data.items[1].stockLevel").value("ok"))
                .andExpect(jsonPath("$.data.items[2].sku").value("ACC-001"))
                .andExpect(jsonPath("$.data.items[2].stockLevel").value("high"))
                .andExpect(jsonPath("$.data.items[2].snapDate").value("2026-09-03"));
    }

    @Test
    void categoryQueryReturnsAllSkusInCategory() throws Exception {
        postJson(Map.of("category", "audio"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.items[1].sku").value("AUD-002"))
                .andExpect(jsonPath("$.data.items[2].sku").value("AUD-003"))
                .andExpect(jsonPath("$.data.items[3].sku").value("AUD-004"))
                .andExpect(jsonPath("$.data.items[3].category").value("audio"))
                .andExpect(jsonPath("$.data.items[3].snapDate").value("2026-09-03"));
    }

    @Test
    void unknownSkuGoesToMissingNotFailure() throws Exception {
        postJson(Map.of("skus", List.of("AUD-001", "NOPE-999")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].sku").value("AUD-001"))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(1)))
                .andExpect(jsonPath("$.data.missingSkus[0]").value("NOPE-999"));
    }

    @Test
    void skusAndCategoryTogetherRejected() throws Exception {
        postJson(Map.of("skus", List.of("AUD-001"), "category", "audio"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 与 category 只能二选一"))
                .andExpect(jsonPath("$.data").value(nullValue()));
    }

    @Test
    void emptyRequestBodyAndEmptySkusRejected() throws Exception {
        postJson("{}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 与 category 不能同时为空"));
        postJson(Map.of("skus", List.of()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400));
    }

    @Test
    void moreThanTwentySkusRejected() throws Exception {
        List<String> manySkus = IntStream.rangeClosed(1, 21)
                .mapToObj(i -> "SKU-" + i)
                .collect(Collectors.toList());
        postJson(Map.of("skus", manySkus))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(400))
                .andExpect(jsonPath("$.message").value("skus 数量不能超过 20"));
    }

    @Test
    void levelsRespectBoundariesAndLatestDateWins() throws Exception {
        // 四个 SKU 各插入 09-04 行（晚于种子最新 09-03），覆盖四档边界：0/19/200/250。
        jdbc.execute("""
                INSERT INTO inventory_snapshot (sku_code, snap_date, stock_qty, created_at, updated_at) VALUES
                ('AUD-002', '2026-09-04', 250, '2026-09-04 09:00:00.000', '2026-09-04 09:00:00.000'),
                ('CHG-002', '2026-09-04', 200, '2026-09-04 09:00:00.000', '2026-09-04 09:00:00.000'),
                ('CHG-003', '2026-09-04', 19,  '2026-09-04 09:00:00.000', '2026-09-04 09:00:00.000'),
                ('ACC-002', '2026-09-04', 0,   '2026-09-04 09:00:00.000', '2026-09-04 09:00:00.000')
                """);
        postJson(Map.of("skus", List.of("CHG-003", "AUD-002", "ACC-002", "CHG-002")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.missingSkus", hasSize(0)))
                .andExpect(jsonPath("$.data.items[0].sku").value("CHG-003"))
                .andExpect(jsonPath("$.data.items[0].availableQty").value(19))
                .andExpect(jsonPath("$.data.items[0].stockLevel").value("low"))
                .andExpect(jsonPath("$.data.items[1].sku").value("AUD-002"))
                .andExpect(jsonPath("$.data.items[1].availableQty").value(250))
                .andExpect(jsonPath("$.data.items[1].stockLevel").value("high"))
                .andExpect(jsonPath("$.data.items[2].sku").value("ACC-002"))
                .andExpect(jsonPath("$.data.items[2].availableQty").value(0))
                .andExpect(jsonPath("$.data.items[2].stockLevel").value("out"))
                .andExpect(jsonPath("$.data.items[3].sku").value("CHG-002"))
                .andExpect(jsonPath("$.data.items[3].availableQty").value(200))
                .andExpect(jsonPath("$.data.items[3].stockLevel").value("ok"))
                .andExpect(jsonPath("$.data.items[3].snapDate").value("2026-09-04"));
    }
}
