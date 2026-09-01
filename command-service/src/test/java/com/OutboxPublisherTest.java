package com;

import fleetmind.events.DispatchAction;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * P15 pin: the lease guard sits at the very top of drainOutbox(). A follower
 * must not touch the outbox table at all — not even the SELECT — otherwise
 * running two instances doubles the DB polling the lease exists to prevent.
 */
class OutboxPublisherTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, DispatchAction> kafka = mock(KafkaTemplate.class);
    private final OutboxLeaderLease lease = mock(OutboxLeaderLease.class);

    private final OutboxPublisher publisher = new OutboxPublisher(jdbc, kafka, lease);

    @Test
    void followerNeverTouchesTheOutboxTable() {
        when(lease.tryAcquire()).thenReturn(false);

        publisher.drainOutbox();

        verifyNoInteractions(jdbc, kafka);
    }

    @Test
    void leaderPollsTheOutbox() {
        when(lease.tryAcquire()).thenReturn(true);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());

        publisher.drainOutbox();

        verify(jdbc).queryForList(anyString());
    }
}
