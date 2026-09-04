package com;

import fleetmind.events.GpsPing;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class DriverProjection {
    private final DriverRepository driverRepository;
    private final SseHub sseHub;
    private final MeterRegistry meterRegistry;

    @KafkaListener(topics = "gps.pings", groupId = "command-service",
                   containerFactory = "gpsBatchListenerFactory")
    @Transactional
    public void onPings(List<ConsumerRecord<String, GpsPing>> records, Acknowledgment ack) {
        Map<String, GpsPing> latest = new LinkedHashMap<>();
        for (ConsumerRecord<String, GpsPing> r : records) {
            latest.put(r.value().getDriverId(), r.value());
        }

        driverRepository.upsertPositions(latest.values());

        meterRegistry.counter("gps.pings.received").increment(records.size());
        meterRegistry.counter("gps.rows.written").increment(latest.size());
        meterRegistry.summary("gps.batch.size").record(records.size());

        for (GpsPing p : latest.values()) {
            sseHub.publish("driver", Map.of(
                    "driverId", p.getDriverId(),
                    "lat", p.getLat(),
                    "lng", p.getLng(),
                    "status", p.getStatus().name(),
                    "speed", p.getSpeedKmph()
            ));
        }
        ack.acknowledge();
    }
}
