package com;

import fleetmind.events.EtaUpdate;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.StoreQueryParameters;
import org.apache.kafka.streams.errors.InvalidStateStoreException;
import org.apache.kafka.streams.state.QueryableStoreType;
import org.apache.kafka.streams.state.QueryableStoreTypes;
import org.apache.kafka.streams.state.ReadOnlyKeyValueStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.config.StreamsBuilderFactoryBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/state")
@RequiredArgsConstructor
public class StateQueryController {
    private final StreamsBuilderFactoryBean streamsBuilderFactoryBean;
    @GetMapping("/eta/{orderId}")
    public ResponseEntity<?> getEta(@PathVariable String orderId)
    {
        KafkaStreams streams =streamsBuilderFactoryBean.getKafkaStreams();
        if (streams == null || streams.state() != KafkaStreams.State.RUNNING) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("Kafka Streams is still starting...");
        }
        try{
            ReadOnlyKeyValueStore<String, EtaUpdate> store=
                    streams.store(
                            StoreQueryParameters.fromNameAndType(
                                    "etaStore",
                                    QueryableStoreTypes.keyValueStore()
                            )
                    );
            EtaUpdate eta=store.get(orderId);
            if (eta == null) {
                return ResponseEntity.notFound().build();
            }


            EtaResponse response = new EtaResponse(
                    eta.getOrderId(),
                    eta.getDriverId(),
                    eta.getEtaMinutes(),
                    eta.getRemainingMeters(),
                    eta.getComputedTs().toString()
            );
            return ResponseEntity.ok(response);
        }catch (InvalidStateStoreException e) {
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body("State store is not ready yet.");
        }
    }
}
