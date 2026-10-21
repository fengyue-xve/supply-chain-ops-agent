package com.demo.flowagent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import com.demo.flowagent.fixtures.FixtureLoader;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"FLOW_DEMO_RESET_ENABLED=true","FLOW_DEMO_RESET_KEY=synthetic-reset-test-only","spring.datasource.url=jdbc:h2:mem:flow-reset-test;DB_CLOSE_DELAY=-1"})
@AutoConfigureMockMvc
class DemoResetTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired RequestCounters counters;
    @Autowired FixtureLoader fixtures;
    @Test void readerAndAgentDoNotHaveDemoResetAuthority() throws Exception {
        for(String token:new String[]{"token-reader-1","token-agent-1","token-approver-1"})
            mvc.perform(post("/api/flow/demo/reset").header("X-Demo-Token",token).contentType("application/json").content("{\"confirmed\":true}"))
                    .andExpect(status().isForbidden());
    }
    @Test void resetNeedsExplicitConfirmation() throws Exception {
        mvc.perform(post("/api/flow/demo/reset").header("X-Demo-Reset-Key","synthetic-reset-test-only").contentType("application/json").content("{\"confirmed\":false}"))
                .andExpect(status().isBadRequest());
    }
    @Test void resetRestoresVersionAndFaultCountersWithoutErasingAudit() throws Exception {
        db.update("UPDATE form_record SET business_version=99,mark_value='SYNTHETIC',demo_fault_remaining=0 WHERE record_id='R-1001'");
        db.update("UPDATE form_record SET demo_fault_remaining=0 WHERE demo_fault IS NOT NULL");
        counters.recordApplyHit(); counters.recordReplayHit();
        int before=db.queryForObject("SELECT COUNT(*) FROM flow_audit",Integer.class);
        mvc.perform(post("/api/flow/demo/reset").header("X-Demo-Reset-Key","synthetic-reset-test-only").contentType("application/json").content("{\"confirmed\":true}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.reset").value(true));
        int version=fixtures.records().stream().filter(row->row.recordId().equals("R-1001")).findFirst().orElseThrow().businessVersion();
        assertEquals(version,db.queryForObject("SELECT business_version FROM form_record WHERE record_id='R-1001'",Integer.class));
        assertEquals(0,db.queryForObject("SELECT COUNT(*) FROM form_record WHERE demo_fault IS NOT NULL AND demo_fault_remaining<>1",Integer.class));
        assertEquals(0,counters.applyEndpointHits()); assertEquals(0,counters.replayEndpointHits());
        assertEquals(before+1,db.queryForObject("SELECT COUNT(*) FROM flow_audit",Integer.class));
    }
}
