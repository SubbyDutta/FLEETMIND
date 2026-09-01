package com;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * P15 pins for the lease state machine. Four outcomes exist and each maps to a
 * distinct Redis interaction:
 *   1. key absent, SET NX EX succeeds            -> leader (fresh acquire)
 *   2. key present + value is MINE (Lua renews)  -> leader (incumbent)
 *   3. key present + value is someone else's     -> follower
 *   4. Redis unreachable                          -> leader anyway (fail-OPEN:
 *      efficiency lock — SKIP LOCKED keeps duplicates correct downstream)
 */
class OutboxLeaderLeaseTest {

    private StringRedisTemplate redis;
    private ValueOperations<String, String> values;
    private OutboxLeaderLease lease;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redis = mock(StringRedisTemplate.class);
        values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        lease = new OutboxLeaderLease(redis, new SimpleMeterRegistry());
    }

    @Test
    void freshAcquireMakesUsLeader() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);

        assertThat(lease.tryAcquire()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void incumbentRenewalKeepsUsLeader() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(1L);

        assertThat(lease.tryAcquire()).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void leaseHeldByAnotherInstanceMakesUsFollower() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);

        assertThat(lease.tryAcquire()).isFalse();
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostLeadershipIsReportedAfterHavingLed() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        assertThat(lease.tryAcquire()).isTrue();

        // next tick: someone else holds the key and the renew script says "not yours"
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(false);
        when(redis.execute(any(RedisScript.class), anyList(), any(Object[].class))).thenReturn(0L);
        assertThat(lease.tryAcquire()).isFalse();
    }

    @Test
    void redisOutageFailsOpen() {
        when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
                .thenThrow(new RedisConnectionFailureException("redis down"));

        assertThat(lease.tryAcquire()).isTrue();
    }
}
