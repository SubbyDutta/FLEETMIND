package com;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import fleetmind.events.DispatchAction;
import fleetmind.events.DispatchActionType;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Component
@RequiredArgsConstructor
public class OutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, DispatchAction> dispatchKafkaTemplate;
    private final ObjectMapper mapper = new ObjectMapper();

    @Scheduled(fixedDelay = 2000)
    @Transactional
    public void drainOutbox() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, aggregate_id, payload
                FROM outbox
                WHERE published = false
                ORDER BY created_at
                LIMIT 50
                FOR UPDATE SKIP LOCKED
                """);

        for (Map<String, Object> row : rows) {
            UUID id = (UUID) row.get("id");
            String orderId = (String) row.get("aggregate_id");
            try {
                DispatchAction action = toAvro(row.get("payload").toString());

                dispatchKafkaTemplate.send("dispatch.actions", orderId, action)
                        .get(5, TimeUnit.SECONDS);
                jdbc.update("UPDATE outbox SET published = true WHERE id = ?", id);
            } catch (Exception e) {

                throw new IllegalStateException("outbox publish failed for row " + id, e);
            }
        }
        if (!rows.isEmpty()) {
            log.info("outbox: published {} action(s)", rows.size());
        }
    }

    private DispatchAction toAvro(String json) throws Exception {
        JsonNode p = mapper.readTree(json);
        return DispatchAction.newBuilder()
                .setOrderId(p.get("orderId").asText())
                .setAction(DispatchActionType.valueOf(p.get("action").asText()))
                .setTargetId(p.hasNonNull("targetId") ? p.get("targetId").asText() : null)
                .setRequestedTs(Instant.ofEpochMilli(p.get("requestedTs").asLong()))
                .build();
    }
}