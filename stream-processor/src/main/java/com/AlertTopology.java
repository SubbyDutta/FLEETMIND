package com;

import com.fleetmind.avro.MovementAgg;
import fleetmind.events.*;
import io.confluent.kafka.streams.serdes.avro.SpecificAvroSerde;

import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.serializer.JsonSerde;
import org.apache.kafka.streams.KeyValue;

import java.time.Duration;

@Configuration
public class AlertTopology {
    @Bean
    public KStream<String, AlertEvent> slaBreachStream(
            KTable<String, EtaUpdate> etaUpdateKTable,
            KTable<String, OrderEvent> orderEventKTable,
            Serde<String> stringSerde,
            SpecificAvroSerde<EtaUpdate> etaUpdateSpecificAvroSerde,
            SpecificAvroSerde<OrderEvent> orderEventSpecificAvroSerde,
            SpecificAvroSerde<AlertEvent> alertEventSpecificAvroSerde,
            SpecificAvroSerde<MovementAgg> movementAggSpecificAvroSerde
    ){
        KStream<String,EtaUpdate> etaStream=etaUpdateKTable.toStream();

        KStream<String,AlertEvent> alerts=etaStream.join(
                orderEventKTable,
                (eta,order) -> SlaBreachDetector.check(order,eta),
                Joined.with(stringSerde,etaUpdateSpecificAvroSerde,orderEventSpecificAvroSerde)
        );
        KStream<String,AlertEvent> breaches=alerts.filter((orderId,alert)->alert!=null);
        breaches.to("alerts", Produced.with(stringSerde,alertEventSpecificAvroSerde));
        return breaches;

    }

    @Bean
    public KStream<String, AlertEvent> stuckDriverStream(

           KStream<String,GpsPing> gpsPingStream,

            Serde<String> stringSerde,

            SpecificAvroSerde<GpsPing> gpsSerde,

            SpecificAvroSerde<MovementAgg> movementAggSerde,

            SpecificAvroSerde<AlertEvent> alertSerde

    ) {


        KStream<String, AlertEvent> alerts =
                gpsPingStream
                        .groupByKey()
                        .windowedBy(
                                TimeWindows
                                        .ofSizeAndGrace(
                                                Duration.ofMinutes(8),
                                                Duration.ofMinutes(1)
                                        )
                                        .advanceBy(
                                                Duration.ofMinutes(1)
                                        )
                        )
                        .aggregate(
                                MovementAggUtil::empty,
                                (driverId,ping,agg)->
                                        MovementAggUtil.add(
                                                agg,
                                                ping
                                        ),
                                Materialized.with(
                                        stringSerde,
                                        movementAggSerde
                                )
                        )
                        .suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
                        .toStream()
                        .filter(
                                (windowKey, agg) ->
                                        StuckDetector.isStuck(agg)
                        )
                        .map(
                                (windowKey, agg)
                                        -> KeyValue.pair(
                                        windowKey.key(),
                                        StuckDetector.alertEvent(
                                                windowKey,
                                                agg
                                        )
                                )
                        );

        alerts.to(

                "alerts",

                Produced.with(

                        stringSerde,

                        alertSerde
                )
        );

        return alerts;
    }

    @Bean
    KStream<String,AlertEvent> idleDriverStream(
            KStream<String,GpsPing> gpsPingStream,

            Serde<String> stringSerde,

            SpecificAvroSerde<GpsPing> gpsSerde,

            SpecificAvroSerde<MovementAgg> movementAggSerde,

            SpecificAvroSerde<AlertEvent> alertSerde

    ){

      KStream<String,AlertEvent> alerts=gpsPingStream.groupByKey()
              .windowedBy(TimeWindows.ofSizeAndGrace(Duration.ofMinutes(5),Duration.ofMinutes(1)))
              .aggregate(
                MovementAggUtil::empty,
                (driverId,ping,agg)->
                        MovementAggUtil.add(
                                agg,
                                ping
                        ),
                Materialized.with(
                        stringSerde,
                        movementAggSerde
                )
        ).suppress(Suppressed.untilWindowCloses(Suppressed.BufferConfig.unbounded()))
              .toStream()
              .filter((windowKey,agg)->IdleDetector.isIdle(agg))
              .map((windowKey,agg)->KeyValue.pair(windowKey.key(), IdleDetector.buildAlert(windowKey,agg)));
        alerts.to(

                "alerts",

                Produced.with(

                        stringSerde,

                        alertSerde
                )
        );
     return alerts;
    }
}
