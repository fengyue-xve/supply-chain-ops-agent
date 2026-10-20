package com.demo.supplyagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.StreamUtils;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 测试隔离验证（思想 11：测试脱离生产）：
 * - test profile 数据源指向 supply_agent_test（不是 dev 库）；
 * - 密码来自环境变量 SUPPLY_DB_PASSWORD（不 import .env.local 文件、不读生产 .env）。
 */
@SpringBootTest
@ActiveProfiles("test")
class ProfileIsolationTest {

    @Autowired
    private Environment env;

    @Test
    void testProfilePointsToTestDatabase() {
        String url = env.getProperty("spring.datasource.url");
        assertThat(url).isNotBlank().contains("supply_agent_test").doesNotContain("supply_agent?");
    }

    @Test
    void testPasswordComesFromEnvironmentNotEnvFile() throws Exception {
        String password = env.getProperty("spring.datasource.password");
        assertThat(password).isNotBlank().isEqualTo(System.getenv("SUPPLY_DB_PASSWORD"));

        String testYml = StreamUtils.copyToString(
                new ClassPathResource("application-test.yml").getInputStream(), StandardCharsets.UTF_8);
        assertThat(testYml).doesNotContain("spring.config.import");
    }

    @Test
    void devProfileIsTheOnlyOneImportingLocalEnvFile() throws Exception {
        String devYml = StreamUtils.copyToString(
                new ClassPathResource("application-dev.yml").getInputStream(), StandardCharsets.UTF_8);
        assertThat(devYml).contains(".env.local");
    }
}
