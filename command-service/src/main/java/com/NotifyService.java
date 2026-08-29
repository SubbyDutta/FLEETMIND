package com;

import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class NotifyService {
    private final JdbcTemplate jdbc;
    private final OutboxRepository outbox;
    @Transactional
    public String notifyCustomer(String orderId,String message,String reason)
    {
        Integer exists=jdbc.queryForObject(
                "SELECT count(*) FROM orders WHERE id=?", Integer.class,orderId
        );
        if(exists==null || exists==0)
        {
            throw new ToolRejection("order not found: " + orderId);
        }
        if (message == null || message.isBlank()) {
            throw new ToolRejection("notification message must not be empty");
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("orderId", orderId);
        payload.put("action", "NOTIFY");
        payload.put("targetId", null);   // customer is implied by the order
        payload.put("requestedTs", System.currentTimeMillis());
        payload.put("message", message); // not in the Avro record yet — see note
        payload.put("reason", reason);

        outbox.insert("order", orderId, "DispatchAction", payload);
        return "notification queued for customer of order " + orderId;
    }
}
