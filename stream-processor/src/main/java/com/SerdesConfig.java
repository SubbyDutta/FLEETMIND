package com;


import com.fleetmind.avro.MovementAgg;
import fleetmind.events.*;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.config.TopicConfig;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.support.serializer.JsonSerde;

import java.util.Map;

@Configuration
public class SerdesConfig {
    @Value("${spring.kafka.streams.properties.schema.registry.url}")
    private String schemaRegistryUrl;
    @Bean
    public Serde<String> stringSerde()
    {
        return Serdes.String();
    }
    @Bean
    public SpecificAvroSerde<GpsPing> gpsPingSerde()
    {
        SpecificAvroSerde<GpsPing> serde=new SpecificAvroSerde<>();
        serde.configure(Map.of("schema.registry.url",schemaRegistryUrl),false);
        return serde;

    }
    @Bean
    public SpecificAvroSerde<OrderEvent> orderEventSerde()
    {
        SpecificAvroSerde<OrderEvent> serde=new SpecificAvroSerde<>();
        serde.configure(Map.of("schema.registry.url",schemaRegistryUrl),false);
        return serde;

    }
    @Bean
    public SpecificAvroSerde<DispatchAction> dispatchActionSerde()
    {
        SpecificAvroSerde<DispatchAction> serde=new SpecificAvroSerde<>();
        serde.configure(Map.of("schema.registry.url",schemaRegistryUrl),false);
        return serde;

    }
    @Bean
    public SpecificAvroSerde<MovementAgg> movementAggSerde()
    {
        SpecificAvroSerde<MovementAgg> serde=new SpecificAvroSerde<>();
        serde.configure(Map.of("schema.registry.url",schemaRegistryUrl),false);
        return serde;
    }

    @Bean
    public SpecificAvroSerde<EtaUpdate> etaUpdateSerde()
    {
        SpecificAvroSerde<EtaUpdate> serde=new SpecificAvroSerde<>();
        serde.configure(Map.of("schema.registry.url",schemaRegistryUrl),false);
        return serde;
    }
    @Bean
    public SpecificAvroSerde<AlertEvent> alertSerde()
    {
        SpecificAvroSerde<AlertEvent> serde=new SpecificAvroSerde<>();
        serde.configure(Map.of("schema.registry.url",schemaRegistryUrl),false);
        return serde;

    }
    @Bean
    public NewTopic alertsTopic() {
        return TopicBuilder.name("alerts")
                .partitions(6)
                .replicas(1)
                .config(TopicConfig.RETENTION_MS_CONFIG, "604800000")
                .build();
    }




}
