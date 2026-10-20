package com.demo.supplyagent.web;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 数据库连接失败时错误可识别：指向本机不存在的端口（3399），/health 应返回
 * 503 + status=DOWN + db=DOWN + detail 含可读异常信息，而不是 500 空白或挂死。
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:mysql://127.0.0.1:3399/supply_agent_test?useSSL=false&connectTimeout=1500&socketTimeout=1500",
        "spring.datasource.hikari.connection-timeout=2000",
        // W2-D1:本类不挂 test profile(验证默认上下文也可识别 DB 故障),JWT 启动强校验需 secret,
        // 故内联 test-only 密钥(非真实 secret,与 application-test.yml 同值)
        "app.jwt.secret=supply-agent-test-only-jwt-secret-0123456789abcdef"
})
@AutoConfigureMockMvc
class ConnectionFailureTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthShowsRecognizableDbDown() throws Exception {
        String detail = mockMvc.perform(get("/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.db").value("DOWN"))
                .andExpect(jsonPath("$.detail").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        org.assertj.core.api.Assertions.assertThat(detail.toLowerCase())
                .containsAnyOf("communications", "refused", "connect");
    }
}
