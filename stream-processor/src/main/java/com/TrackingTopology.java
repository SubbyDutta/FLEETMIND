package com;

import fleetmind.events.EtaUpdate;
import fleetmind.events.GpsPing;
import fleetmind.events.OrderEvent;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration

public class TrackingTopology {

    @Bean
    public KStream<String, GpsPing> gpsPingStream(
            StreamsBuilder streamsBuilder,
            Serde<String> stringSerde,
            SpecificAvroSerde<GpsPing> gpsPingSpecificAvroSerde
    ) {
        return streamsBuilder.stream(
                "gps.pings",
                Consumed.with(stringSerde, gpsPingSpecificAvroSerde)
                        .withTimestampExtractor(new GpsPingTimestampExtractor())
        );
    }

    @Bean
    public KTable<String, GpsPing> gpsPingKTable(
            KStream<String, GpsPing> gpsPingStream,
            Serde<String> stringSerde,
            SpecificAvroSerde<GpsPing> gpsPingSpecificAvroSerde
    ) {
        return gpsPingStream.toTable(
                Materialized.as("driverPositions")
        );
    }
    @Bean
    public KTable<String, OrderEvent> orderEventKTable(
            StreamsBuilder streamsBuilder,
            Serde<String> stringSerde,
            SpecificAvroSerde<OrderEvent> orderEventSpecificAvroSerde
    ){
        return streamsBuilder.table("orders",Consumed.with(stringSerde,orderEventSpecificAvroSerde),Materialized.as("orderState"));
    }
    @Bean
    public KTable<String, EtaUpdate> etaKTable(
            KTable<String,OrderEvent> orderEventKTable,
            KTable<String,GpsPing> gpsPingKTable,
            Serde<String> stringSerde,
            SpecificAvroSerde<EtaUpdate> etaSerde
    ){
        KTable<String,EtaUpdate> etaTable=
                orderEventKTable.join(
                        gpsPingKTable,
                        OrderEvent::getAssignedDriverId,
                        EtaCalculator::calculate,
                        Materialized.as("etaStore")
                );
        etaTable.toStream().to("eta.updates", Produced.with(stringSerde,etaSerde));
        return etaTable;
    }
}
