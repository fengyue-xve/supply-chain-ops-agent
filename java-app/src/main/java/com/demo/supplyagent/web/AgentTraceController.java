package com.demo.supplyagent.web;

import com.demo.supplyagent.gateway.AgentTraceClient;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Existing JWT authentication applies; Python additionally binds traces to credential hashes. */
@RestController
public class AgentTraceController {
    private final AgentTraceClient traces;
    public AgentTraceController(AgentTraceClient traces) { this.traces = traces; }
    @GetMapping("/api/agent/runs/{requestId}")
    public JsonNode read(@PathVariable String requestId, HttpServletRequest request) {
        return traces.request(requestId, request.getHeader(HttpHeaders.AUTHORIZATION), false);
    }
    @PostMapping("/api/agent/runs/{requestId}/cancel")
    public JsonNode cancel(@PathVariable String requestId, HttpServletRequest request) {
        return traces.request(requestId, request.getHeader(HttpHeaders.AUTHORIZATION), true);
    }
}
