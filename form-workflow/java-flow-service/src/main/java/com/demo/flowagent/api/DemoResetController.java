package com.demo.flowagent.api;

import com.demo.flowagent.domain.AuditEventType;
import com.demo.flowagent.domain.ErrorCode;
import com.demo.flowagent.domain.FlowException;
import com.demo.flowagent.fixtures.FixtureLoader;
import com.demo.flowagent.store.RecordStore;
import com.demo.flowagent.store.AuditStore;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

/** Dedicated demo-only reset key is never an Agent/reader capability. */
@RestController
public class DemoResetController {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final FixtureLoader fixtures;
    private final RecordStore records;
    private final AuditStore audit;
    private final RequestCounters counters;
    private final boolean enabled;
    private final String key;
    public DemoResetController(JdbcTemplate db,TransactionTemplate tx,FixtureLoader fixtures,RecordStore records,AuditStore audit,RequestCounters counters,
                               @Value("${FLOW_DEMO_RESET_ENABLED:false}") boolean enabled,@Value("${FLOW_DEMO_RESET_KEY:}") String key) {
        this.db=db;this.tx=tx;this.fixtures=fixtures;this.records=records;this.audit=audit;this.counters=counters;this.enabled=enabled;this.key=key;
    }
    public record ResetRequest(boolean confirmed) {}
    @PostMapping("/api/flow/demo/reset")
    public Map<String,Object> reset(@RequestBody ResetRequest body,HttpServletRequest request) {
        String provided=request.getHeader("X-Demo-Reset-Key");
        if (!enabled || key.isBlank() || provided==null || !MessageDigest.isEqual(key.getBytes(StandardCharsets.UTF_8),provided.getBytes(StandardCharsets.UTF_8)))
            throw new FlowException(ErrorCode.FORBIDDEN_ROLE,"演示重置未授权",Map.of());
        if(!body.confirmed()) throw new FlowException(ErrorCode.BAD_REQUEST,"需要明确确认",Map.of());
        try(var connection=db.getDataSource().getConnection()) {
            if(!connection.getMetaData().getURL().startsWith("jdbc:h2:mem:")) throw new FlowException(ErrorCode.FORBIDDEN_ROLE,"仅允许重置内存演示库",Map.of());
        } catch(java.sql.SQLException error) { throw new IllegalStateException(error); }
        tx.executeWithoutResult(status -> {
            db.update("DELETE FROM flow_approval"); db.update("DELETE FROM flow_run"); db.update("DELETE FROM form_record");
            fixtures.records().forEach(records::seedIfAbsent);
            audit.append("demo-reset",AuditEventType.DEMO_BASELINE_RESET,"demo-admin",Map.of("records",fixtures.records().size()));
        });
        counters.reset();
        return Map.of("reset",true,"auditPreserved",true);
    }
}
