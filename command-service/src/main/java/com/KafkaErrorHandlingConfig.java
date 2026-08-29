package com;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaErrorHandlingConfig {
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(){
        ExponentialBackOffWithMaxRetries backoff=new ExponentialBackOffWithMaxRetries(10);
        backoff.setInitialInterval(1_000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(10_000L);
        return new DefaultErrorHandler(backoff);
    }
}
