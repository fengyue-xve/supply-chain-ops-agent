package com.demo.supplyagent.web;

import com.demo.supplyagent.support.TestDbInitializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W1-D5 工具发现端点：只读目录，不执行任何工具、不依赖库数据。
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
// W2-D1:工具发现属业务接口(需登录);认证语义由 security.AuthFlowIntegrationTest 验证,故只装配认证上下文
@WithMockUser
class ToolDiscoveryControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeAll
    static void initDb(@Autowired DataSource dataSource) throws Exception {
        TestDbInitializer.init(dataSource);
    }

    @Test
    void listToolsReturnsFiveSummariesWithoutSchemas() throws Exception {
        mvc.perform(get("/api/agent/tools"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.message").value("ok"))
                .andExpect(jsonPath("$.data.total").value(5))
                .andExpect(jsonPath("$.data.tools", hasSize(5)))
                .andExpect(jsonPath("$.data.tools[0].name").value("query-stock-level"))
                .andExpect(jsonPath("$.data.tools[1].name").value("query-sales"))
                .andExpect(jsonPath("$.data.tools[2].name").value("suggest-replenish"))
                .andExpect(jsonPath("$.data.tools[3].name").value("create-purchase-order"))
                .andExpect(jsonPath("$.data.tools[4].name").value("confirm-po"))
                .andExpect(jsonPath("$.data.tools[0].description").isNotEmpty())
                .andExpect(jsonPath("$.data.tools[0].readOnly").value(true))
                .andExpect(jsonPath("$.data.tools[0].requiresApproval").value(false))
                .andExpect(jsonPath("$.data.tools[0].timeoutMs").value(3000))
                .andExpect(jsonPath("$.data.tools[0].version").value("1"))
                .andExpect(jsonPath("$.data.tools[0].inputSchema").doesNotExist())
                .andExpect(jsonPath("$.data.tools[3].readOnly").value(false))
                .andExpect(jsonPath("$.data.tools[3].requiresApproval").value(true))
                .andExpect(jsonPath("$.data.tools[3].timeoutMs").value(5000))
                .andExpect(jsonPath("$.data.tools[4].readOnly").value(false))
                .andExpect(jsonPath("$.data.tools[4].requiresApproval").value(false))
                .andExpect(jsonPath("$.data.tools[4].timeoutMs").value(5000));
    }

    @Test
    void schemaEndpointReturnsFullContractWithoutGeneratedAt() throws Exception {
        MvcResult result = mvc.perform(get("/api/agent/tools/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.path("version").asText()).isEqualTo("1");
        assertThat(data.path("tools").size()).isEqualTo(5);
        assertThat(data.at("/tools/0/name").asText()).isEqualTo("query-stock-level");
        assertThat(data.at("/tools/0/readOnly").asBoolean()).isTrue();
        assertThat(data.at("/tools/0/requiresApproval").asBoolean()).isFalse();
        assertThat(data.at("/tools/0/timeoutMs").asLong()).isEqualTo(3000L);
        assertThat(data.at("/tools/0/inputSchema/properties/skus/maxItems").asInt()).isEqualTo(20);
        assertThat(data.at("/tools/0/inputSchema/oneOf").isArray()).isTrue();
        assertThat(data.at("/tools/0/inputSchema/oneOf").size()).isEqualTo(2);
        assertThat(data.at("/tools/0/outputSchema/properties/missingSkus").isMissingNode()).isFalse();
        assertThat(data.at("/tools/0/outputSchema/required").size()).isEqualTo(3);
        assertThat(data.path("generatedAt").isMissingNode()).isTrue();

        JsonNode salesInput = data.at("/tools/1/inputSchema");
        assertThat(salesInput.at("/properties/days/enum/0").asInt()).isEqualTo(7);
        assertThat(salesInput.at("/properties/days/enum/1").asInt()).isEqualTo(30);

        JsonNode suggestInput = data.at("/tools/2/inputSchema");
        assertThat(suggestInput.at("/properties/targetCoverDays/default").asInt()).isEqualTo(14);
        assertThat(suggestInput.at("/properties/targetCoverDays/enum").size()).isEqualTo(3);

        // W3-③ 写工具完整契约：只读=false、仅 create 需要人工确认
        JsonNode createTool = data.at("/tools/3");
        assertThat(createTool.at("/name").asText()).isEqualTo("create-purchase-order");
        assertThat(createTool.at("/readOnly").asBoolean()).isFalse();
        assertThat(createTool.at("/requiresApproval").asBoolean()).isTrue();
        assertThat(createTool.at("/timeoutMs").asLong()).isEqualTo(5000L);
        assertThat(createTool.at("/inputSchema/required/0").asText()).isEqualTo("items");
        assertThat(createTool.at("/inputSchema/properties/items/maxItems").asInt()).isEqualTo(20);
        assertThat(createTool.at("/outputSchema/required").size()).isEqualTo(3);

        JsonNode confirmTool = data.at("/tools/4");
        assertThat(confirmTool.at("/name").asText()).isEqualTo("confirm-po");
        assertThat(confirmTool.at("/readOnly").asBoolean()).isFalse();
        assertThat(confirmTool.at("/requiresApproval").asBoolean()).isFalse();
        assertThat(confirmTool.at("/inputSchema/required/0").asText()).isEqualTo("poNo");
        assertThat(confirmTool.at("/outputSchema/required").size()).isEqualTo(4);
    }
}
