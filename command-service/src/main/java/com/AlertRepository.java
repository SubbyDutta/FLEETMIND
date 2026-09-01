package com;

import fleetmind.events.AlertEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class AlertRepository {

    private final JdbcTemplate jdbc;

    public int insert(AlertEvent alert) {
         return jdbc.update(
                """
                INSERT INTO alerts (order_id, driver_id, type, severity, reason)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT DO NOTHING
                """,
                alert.getOrderId(),
                alert.getDriverId(),
                alert.getType().name(),
                alert.getSeverity().name(),
                alert.getReason()
        );
    }
    @Cacheable(cacheNames = "alerts", key = "'recent'")
    public List<Map<String, Object>> findRecent() {
        return jdbc.queryForList(
                """
                SELECT id, type, severity, driver_id, order_id, reason, resolved, created_at
                FROM alerts
                WHERE resolved = false
                ORDER BY created_at DESC
                LIMIT 50
                """
        );
    }
}