package com.demo.supplyagent.service;

import com.demo.supplyagent.web.ApiException;
import com.demo.supplyagent.entity.AgentHistory;
import com.demo.supplyagent.mapper.AgentHistoryMapper;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.util.Map;

/** Demo profile + loopback database + explicit fixture marker. Audit/accounts are never erased. */
@Service
@Profile("demo")
public class DemoResetService {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final DataSource source;
    private final AgentHistoryMapper history;
    public DemoResetService(JdbcTemplate db, TransactionTemplate tx, DataSource source, AgentHistoryMapper history) { this.db=db; this.tx=tx; this.source=source; this.history=history; }
    public void reset(boolean confirmed, String operator) {
        if (!confirmed) throw new ApiException(400,"请明确确认重置合成演示数据");
        try (var connection=source.getConnection()) {
            String url=connection.getMetaData().getURL();
            if (!url.matches("jdbc:mysql://(127\\.0\\.0\\.1|localhost):[0-9]+/supply_agent(?:\\?.*)?"))
                throw new ApiException(403,"只允许重置本机独立supply_agent合成库");
        } catch (java.sql.SQLException error) { throw new ApiException(503,"无法验证演示数据源"); }
        try {
            if (!"synthetic-portfolio-v1".equals(db.queryForObject("SELECT marker FROM demo_fixture_guard WHERE id=1",String.class)))
                throw new ApiException(409,"未发现合成演示标记，拒绝重置");
        } catch (org.springframework.dao.DataAccessException error) {
            throw new ApiException(409,"请先在独立演示库执行db/06_demo_reset_guard.sql，未重置任何数据");
        }
        tx.executeWithoutResult(status -> {
            db.queryForList("SELECT sku_code FROM product ORDER BY sku_code FOR UPDATE");
            for (String table : new String[]{"po_confirmation","idempotency_record","purchase_po","agent_task","inventory_snapshot","sale_order","product"})
                db.update("DELETE FROM " + table);
            // Seed files contain DML only. Keeping history/conversations prevents audit rewriting.
            for (String name : new String[]{"seed.sql","demo_case.sql"}) {
                try {
                    String sql=new ClassPathResource("demo-baseline/"+name).getContentAsString(StandardCharsets.UTF_8)
                            .replaceAll("(?m)^USE supply_agent;", "");
                    var connection=DataSourceUtils.getConnection(source);
                    try { ScriptUtils.executeSqlScript(connection,new ByteArrayResource(sql.getBytes(StandardCharsets.UTF_8))); }
                    finally { DataSourceUtils.releaseConnection(connection,source); }
                } catch (java.io.IOException error) { throw new IllegalStateException("Missing packaged demo baseline",error); }
            }
            var event=new AgentHistory();
            var now=java.time.LocalDateTime.now();
            event.setExecutedAt(now); event.setActionType("demo_baseline_reset"); event.setWho("admin"); event.setOperator(operator);
            event.setStatsJson("{\"synthetic\":true}"); event.setCreatedAt(now); event.setUpdatedAt(now);
            history.insert(event);
        });
    }
}
