package com;

import fleetmind.events.DriverState;
import fleetmind.events.GpsPing;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.support.Acknowledgment;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class DriverProjectionTest {

    private final DriverRepository repo = mock(DriverRepository.class);
    private final SseHub sse = mock(SseHub.class);
    private final Acknowledgment ack = mock(Acknowledgment.class);
    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    private final DriverProjection projection = new DriverProjection(repo, sse, meters);

    @Test
    @SuppressWarnings("unchecked")
    void collapsesToLatestPerDriverAndWritesOnce() {
        List<ConsumerRecord<String, GpsPing>> batch = List.of(
                record("A", 1), record("A", 2), record("B", 7), record("A", 3));

        projection.onPings(batch, ack);

        ArgumentCaptor<Collection<GpsPing>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(repo, times(1)).upsertPositions(captor.capture());
        Collection<GpsPing> written = captor.getValue();
        assertThat(written).hasSize(2);
        assertThat(written).extracting(GpsPing::getDriverId).containsExactly("A", "B");
        assertThat(written).filteredOn(p -> p.getDriverId().equals("A"))
                .extracting(GpsPing::getSpeedKmph).containsExactly(3.0);

        verify(sse, times(2)).publish(eq("driver"), any(Map.class));
        verify(ack, times(1)).acknowledge();

        assertThat(meters.counter("gps.pings.received").count()).isEqualTo(4.0);
        assertThat(meters.counter("gps.rows.written").count()).isEqualTo(2.0);
        assertThat(meters.summary("gps.batch.size").max()).isEqualTo(4.0);
    }

    @Test
    void emptyBatchStillAcks() {
        projection.onPings(List.of(), ack);
        verify(repo).upsertPositions(argThat(Collection::isEmpty));
        verify(ack).acknowledge();
    }

    private static ConsumerRecord<String, GpsPing> record(String driverId, double seq) {
        GpsPing ping = GpsPing.newBuilder()
                .setDriverId(driverId)
                .setLat(22.5).setLng(88.3)
                .setSpeedKmph(seq)
                .setStatus(DriverState.TO_DROP)
                .setTs(Instant.now())
                .build();
        return new ConsumerRecord<>("gps.pings", 0, (long) seq, driverId, ping);
    }
}
