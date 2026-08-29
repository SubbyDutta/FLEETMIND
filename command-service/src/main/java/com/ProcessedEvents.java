package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class ProcessedEvents {
    private final JdbcTemplate jdbc;

    public boolean markIfNew(String eventId)
    {
        int inserted=jdbc.update(
                "INSERT INTO processed_events (event_id) VALUES (?) ON CONFLICT DO NOTHING",
                eventId);

        return inserted ==1;
    }

}
