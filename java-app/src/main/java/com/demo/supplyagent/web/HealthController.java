package com.demo.supplyagent.web;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class HealthController {

    private final JdbcTemplate jdbcTemplate;

    public HealthController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        try {
            Integer one = jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            if (one != null && one == 1) {
                body.put("status", "UP");
                body.put("db", "UP");
                return ResponseEntity.ok(body);
            }
            throw new IllegalStateException("SELECT 1 returned unexpected value");
        } catch (Exception e) {
            body.put("status", "DOWN");
            body.put("db", "DOWN");
            body.put("detail", abbreviate(e));
            return ResponseEntity.status(503).body(body);
        }
    }

    private String abbreviate(Exception e) {
        String msg = e.getMessage();
        if (msg == null || msg.isBlank()) {
            msg = e.getClass().getSimpleName();
        }
        msg = msg.replace('\n', ' ').replace('\r', ' ').trim();
        return msg.length() <= 200 ? msg : msg.substring(0, 200) + "...";
    }
}
