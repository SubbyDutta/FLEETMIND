package com;

import io.confluent.kafka.serializers.AbstractKafkaSchemaSerDeConfig;
import io.confluent.kafka.serializers.KafkaAvroSerializer;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
public class KafkaErrorConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${spring.kafka.schema-registry-url:http://localhost:8081}")
    private String schemaRegistryUrl;

    @Bean
    public NewTopic alertsDlt() {
        return TopicBuilder.name("alerts-dlt").partitions(6).replicas(1).build();
    }

    @Bean
    public NewTopic ordersDlt() {
        return TopicBuilder.name("orders-dlt").partitions(6).replicas(1).build();
    }

    @Bean
    public KafkaTemplate<Object, Object> dltAvroTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, KafkaAvroSerializer.class);
        props.put(AbstractKafkaSchemaSerDeConfig.SCHEMA_REGISTRY_URL_CONFIG, schemaRegistryUrl);
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(props));
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public KafkaTemplate<String, byte[]> dltBytesTemplate() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        var template = new KafkaTemplate<>(new DefaultKafkaProducerFactory<String, byte[]>(props));
        template.setObservationEnabled(true);
        return template;
    }

    @Bean
    public DefaultErrorHandler notificationErrorHandler(
            KafkaTemplate<Object, Object> dltAvroTemplate,
            KafkaTemplate<String, byte[]> dltBytesTemplate) {

        Map<Class<?>, KafkaOperations<?, ?>> templates = new LinkedHashMap<>();
        templates.put(byte[].class, dltBytesTemplate);
        templates.put(Object.class, dltAvroTemplate);
        var recoverer = new DeadLetterPublishingRecoverer(templates);

        var backoff = new ExponentialBackOffWithMaxRetries(3);
        backoff.setInitialInterval(1_000L);
        backoff.setMultiplier(2.0);
        backoff.setMaxInterval(4_000L);

        var handler = new DefaultErrorHandler(recoverer, backoff);
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}
