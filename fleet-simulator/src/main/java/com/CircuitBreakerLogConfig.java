package com;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

@Configuration
public class CircuitBreakerLogConfig {
    private static final Logger log = LoggerFactory.getLogger(CircuitBreakerLogConfig.class);

    public CircuitBreakerLogConfig(CircuitBreakerRegistry registry) {
        registry.getAllCircuitBreakers().forEach(this::attach);
        registry.getEventPublisher().onEntryAdded(e -> attach(e.getAddedEntry()));
    }

    private void attach(CircuitBreaker cb) {
        cb.getEventPublisher().onStateTransition(ev ->
                log.warn("CIRCUIT '{}' : {} -> {}",
                        ev.getCircuitBreakerName(),
                        ev.getStateTransition().getFromState(),
                        ev.getStateTransition().getToState()));
    }
}
