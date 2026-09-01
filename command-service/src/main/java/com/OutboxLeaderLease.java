package com;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.UUID;


@Component
public class OutboxLeaderLease {
    private static final Logger log = LoggerFactory.getLogger(OutboxLeaderLease.class);

    private static final String KEY = "fm:lease:outbox-publisher";

    private static final Duration TTL = Duration.ofSeconds(10);


    private static final RedisScript<Long> RENEW = RedisScript.of("""
            if redis.call('GET', KEYS[1]) == ARGV[1] then
              return redis.call('PEXPIRE', KEYS[1], ARGV[2])
            else
              return 0
            end
            """, Long.class);

    private final StringRedisTemplate redis;
    private final String instanceId;
    private volatile boolean leader = false;

    public OutboxLeaderLease(StringRedisTemplate redis, MeterRegistry meterRegistry) {
        this.redis = redis;
        this.instanceId = buildInstanceId();

        Gauge.builder("outbox.leader", this, l -> l.leader ? 1.0 : 0.0)
                .tag("instance_id", instanceId)
                .register(meterRegistry);
        log.info("outbox lease: this instance is {}", instanceId);
    }


    public boolean tryAcquire() {
        try {

            Boolean acquired = redis.opsForValue().setIfAbsent(KEY, instanceId, TTL);
            if (Boolean.TRUE.equals(acquired)) {
                becomeLeader("acquired");
                return true;
            }

            Long renewed = redis.execute(RENEW, List.of(KEY), instanceId,
                    String.valueOf(TTL.toMillis()));
            boolean stillMine = renewed != null && renewed == 1L;
            if (stillMine) {
                leader = true;              // steady state — no log spam
            } else if (leader) {
                leader = false;
                log.warn("outbox lease: LOST leadership (another instance holds the lease)");
            }
            return stillMine;
        } catch (DataAccessException e) {

            if (!leader) {
                log.warn("outbox lease: redis unavailable — failing open, draining anyway ({})",
                        e.toString());
            }
            leader = true;
            return true;
        }
    }

    private void becomeLeader(String how) {
        if (!leader) {
            leader = true;
            log.info("outbox lease: BECAME leader ({}) as {}", how, instanceId);
        }
    }

    private String buildInstanceId() {
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }

        return host + "-" + ProcessHandle.current().pid()
                + "-" + UUID.randomUUID().toString().substring(0, 8);
    }
}