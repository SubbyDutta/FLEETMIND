package com;

import fleetmind.events.GpsPing;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Component
@RequiredArgsConstructor
public class DriverProjection {
    private final ProcessedEvents processedEvents;
    private final DriverRepository driverRepository;
    private final SseHub sseHub;
    @KafkaListener(topics = "gps.pings",groupId = "command-service")
    @Transactional
    public void onPing(ConsumerRecord<String, GpsPing> record, Acknowledgment ack)
    {
        String eventId=record.topic()+" "+record.partition()+" "+record.offset();
       /* if (processedEvents.markIfNew(eventId)) {
            driverRepository.upsertPosition(record.value());
        }*/
        driverRepository.upsertPosition(record.value());
        sseHub.publish("driver", Map.of(
                "driverId", record.value().getDriverId(),
                "lat", record.value().getLat(),
                "lng", record.value().getLng(),
                "status",  record.value().getStatus().name(),
                "speed",  record.value().getSpeedKmph()
        ));
        ack.acknowledge();
    }
}
