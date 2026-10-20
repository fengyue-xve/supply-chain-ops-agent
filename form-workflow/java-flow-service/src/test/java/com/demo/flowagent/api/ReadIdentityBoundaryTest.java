package com.demo.flowagent.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties="spring.datasource.url=jdbc:h2:mem:flow_reader_boundary;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
class ReadIdentityBoundaryTest {
    @Autowired MockMvc mvc;
    @Test void readerMayReadRecordAndStatistics() throws Exception {
        mvc.perform(get("/api/flow/records/R-1001").header("X-Demo-Token", "token-reader-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.sku_code").value("AUD-001"));
        mvc.perform(get("/api/flow/stats").header("X-Demo-Token", "token-reader-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.total_write_count").value(0));
    }
    @Test void readerCannotApproveApplyOrSubmitEvents() throws Exception {
        for (String action : new String[]{"approve", "apply", "replay", "request-approval"}) {
            mvc.perform(post("/api/flow/runs/run-123456abcdef/"+action).header("X-Demo-Token", "token-reader-1"))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(post("/api/flow/events").header("X-Demo-Token", "token-reader-1")
                .contentType("application/json")
                .content("{\"event_id\":\"evt-600\",\"source_record_id\":\"R-1001\",\"flow_type\":\"PRELOAD_OUTER_BOX_MARK\",\"business_version\":3,\"submitted_by\":\"reader-1\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/flow/records/R-1001").header("X-Demo-Token", "token-reader-1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.outer_box_mark").isEmpty());
    }
}
