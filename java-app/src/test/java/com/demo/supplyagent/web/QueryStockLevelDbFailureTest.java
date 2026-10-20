package com.demo.supplyagent.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据库不可达时 query-stock-level 返回统一 500 信封（不裸抛 Spring 默认错误页）。
 * 独立上下文：url 指向未监听端口 3399 + 短连接超时，任何请求都不该碰真实 test 库。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3399/supply_agent_test?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
        "spring.datasource.hikari.connection-timeout=1500"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
// W2-D1:本类职责是 DB 故障 500 信封,认证语义由 security.AuthFlowIntegrationTest 验证,故只装配认证上下文
@WithMockUser
class QueryStockLevelDbFailureTest {

    @Autowired
    private MockMvc mvc;

    @Test
    void dbDownReturnsUnified500Envelope() throws Exception {
        mvc.perform(post("/api/agent/tools/query-stock-level")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"skus\":[\"AUD-001\"]}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value(500))
                .andExpect(jsonPath("$.message").value("数据库访问异常，请稍后重试"))
                .andExpect(jsonPath("$.data").value(nullValue()))
                .andExpect(jsonPath("$.detail").doesNotExist());
    }
}
