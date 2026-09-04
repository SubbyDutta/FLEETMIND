package com;

import fleetmind.events.DriverState;
import fleetmind.events.GpsPing;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

@Slf4j
@RestController
@RequestMapping("/sim")
@RequiredArgsConstructor
public class GpsFloodController {

    private final KafkaTemplate<String, GpsPing> gpsPingKafkaTemplate;

    @PostMapping("/flood")
    public ResponseEntity<Map<String, Object>> flood(@RequestParam(defaultValue = "500") int drivers,
                                                     @RequestParam(defaultValue = "200") int pings,
                                                     @RequestParam(defaultValue = "0") long pauseMsPerRound) {
        long total = (long) drivers * pings;
        Thread.ofVirtual().name("gps-flood").start(() -> run(drivers, pings, pauseMsPerRound, total));
        return ResponseEntity.accepted().body(Map.of(
                "drivers", drivers, "pings", pings, "total", total, "pauseMsPerRound", pauseMsPerRound));
    }

    private void run(int drivers, int pings, long pauseMsPerRound, long total) {
        long t0 = System.nanoTime();
        log.info("FLOOD START drivers={} pings={} total={}", drivers, pings, total);
        for (int seq = 1; seq <= pings; seq++) {
            for (int d = 0; d < drivers; d++) {
                String id = String.format("load-%04d", d);
                GpsPing ping = GpsPing.newBuilder()
                        .setDriverId(id)
                        .setLat(22.50 + ThreadLocalRandom.current().nextDouble(0.15))
                        .setLng(88.30 + ThreadLocalRandom.current().nextDouble(0.15))
                        .setSpeedKmph(seq)
                        .setStatus(DriverState.TO_DROP)
                        .setTs(Instant.now())
                        .build();
                gpsPingKafkaTemplate.send("gps.pings", id, ping);
            }
            if (pauseMsPerRound > 0) sleep(pauseMsPerRound);
        }
        gpsPingKafkaTemplate.flush();
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("FLOOD DONE total={} in {} ms ({} pings/s)", total, ms, ms == 0 ? total : total * 1000 / ms);
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
