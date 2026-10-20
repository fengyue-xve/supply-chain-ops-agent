package com.demo.supplyagent.web;

import com.demo.supplyagent.dto.ApiResponse;
import com.demo.supplyagent.gateway.FormsReadClient;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Existing JWT authentication applies; both viewer/admin may inspect independent preparation records. */
@RestController
@RequestMapping("/api/forms")
public class FormsReadController {
    private final FormsReadClient forms;
    public FormsReadController(FormsReadClient forms) { this.forms = forms; }

    @GetMapping("/records/{recordId}")
    public ApiResponse<JsonNode> record(@PathVariable String recordId) {
        return ApiResponse.ok(forms.record(recordId));
    }

    @GetMapping("/runs/{runId}")
    public ApiResponse<JsonNode> run(@PathVariable String runId) {
        return ApiResponse.ok(forms.run(runId));
    }
}
