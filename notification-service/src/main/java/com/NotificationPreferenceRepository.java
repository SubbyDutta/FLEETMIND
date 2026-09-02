package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
@RequiredArgsConstructor
public class NotificationPreferenceRepository {
    private final JdbcTemplate jdbc;

    public List<NotificationPreference> findRecipients(String tenant, String eventType) {
        return jdbc.query("""
                SELECT email, email_enabled, inapp_enabled
                FROM notification_preferences
                WHERE tenant_id = ? AND event_type = ?
                """,
                (rs, i) -> new NotificationPreference(
                        rs.getString("email"),
                        rs.getBoolean("email_enabled"),
                        rs.getBoolean("inapp_enabled")),
                tenant, eventType);
    }
}
