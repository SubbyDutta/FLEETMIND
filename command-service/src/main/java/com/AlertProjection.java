package com;

import fleetmind.events.AlertEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class AlertProjection {

    private final ProcessedEvents processedEvents;
    private final AlertRepository alertRepository;
    private final SseHub sseHub;

    @KafkaListener(topics = "alerts", groupId = "command-service")
    @Transactional
    public void onAlert(ConsumerRecord<String, AlertEvent> record, Acknowledgment ack) {

        String eventId = record.topic() + "-" + record.partition() + "-" + record.offset();

        if (processedEvents.markIfNew(eventId)) {
           int inserted= alertRepository.insert(record.value());
            if(inserted>0)
            {
            sseHub.publish("alert", Map.of(
                    "type", record.value().getType().name(),
                    "severity",  record.value().getSeverity().name(),
                    "driverId",  record.value().getDriverId() == null ? "" :  record.value().getDriverId(),
                    "orderId",  record.value().getOrderId() == null ? "" :  record.value().getOrderId(),
                    "reason",  record.value().getReason()
            ));}
        }

        ack.acknowledge();
    }
}