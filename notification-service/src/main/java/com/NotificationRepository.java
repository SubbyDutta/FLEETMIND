package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;

@Repository
@RequiredArgsConstructor
public class NotificationRepository {
    private final JdbcTemplate jdbc;

    public void insert(String tenant, String recipient, String eventType, String subject, String body) {
        jdbc.update("""
                INSERT INTO notifications (tenant_id, recipient, event_type, subject, body)
                VALUES (?, ?, ?, ?, ?)
                """,
                tenant, recipient, eventType, subject, body);
    }

    public List<Map<String, Object>> findRecent(String recipient, int limit) {
        return jdbc.queryForList("""
                SELECT id, tenant_id, event_type, subject, body, is_read, created_at
                FROM notifications
                WHERE recipient = ?
                ORDER BY created_at DESC
                LIMIT ?
                """,
                recipient, limit);
    }
}
