package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class NotificationDedupe {
    private final JdbcTemplate jdbc;

    public boolean markIfNew(String eventId) {
        int inserted = jdbc.update(
                "INSERT INTO notification_dedupe (event_id) VALUES (?) ON CONFLICT DO NOTHING",
                eventId);
        return inserted == 1;
    }
}
