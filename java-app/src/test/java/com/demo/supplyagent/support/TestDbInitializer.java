package com.demo.supplyagent.support;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;

/**
 * 测试库自举：把 db/schema.sql + db/seed.sql 灌入当前 DataSource 指向的库。
 * schema.sql 内的 "USE supply_agent;" 在执行前替换为目标库名（本机 test 库或 Testcontainers 随机库）。
 * 幂等：schema 用 CREATE TABLE IF NOT EXISTS，seed 用 INSERT IGNORE / ON DUPLICATE KEY UPDATE，
 * 因此每个测试类 @BeforeAll 重复调用是安全的。
 */
public final class TestDbInitializer {

    private TestDbInitializer() {
    }

    public static void init(DataSource dataSource) throws Exception {
        String targetDb;
        try (Connection conn = dataSource.getConnection()) {
            targetDb = extractDbName(conn.getMetaData().getURL());
        }
        for (String file : new String[]{"schema.sql", "seed.sql"}) {
            String sql = Files.readString(Path.of("..", "db", file), StandardCharsets.UTF_8);
            sql = sql.replace("USE supply_agent;", "USE " + targetDb + ";");
            try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
                for (String statement : sql.split(";")) {
                    String s = statement.trim();
                    if (!s.isEmpty()) {
                        st.execute(s);
                    }
                }
            }
        }
    }

    static String extractDbName(String url) {
        int q = url.indexOf('?');
        if (q > 0) {
            url = url.substring(0, q);
        }
        return url.substring(url.lastIndexOf('/') + 1);
    }
}
