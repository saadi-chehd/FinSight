package com.finsight;

import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class DatabaseHealthController {
    private final JdbcTemplate jdbc;
    public DatabaseHealthController(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @GetMapping("/api/health/database")
    public ResponseEntity<Map<String, String>> database() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(Map.of("status", "UP", "service", "database"));
        } catch (DataAccessException e) {
            return ResponseEntity.status(503).body(Map.of("status", "DOWN", "service", "database"));
        }
    }
}
