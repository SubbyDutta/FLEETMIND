package com;

import fleetmind.events.DispatchAction;
import fleetmind.events.DispatchActionType;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;
import org.springframework.kafka.support.serializer.DeserializationException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class KafkaErrorConfigTest {

    private static final String SOURCE_TOPIC = "dispatch.actions";
    private static final String DLT = "dispatch.actions-dlt";

    private KafkaTemplate<Object, Object> avroTemplate;
    private KafkaTemplate<String, byte[]> bytesTemplate;
    private Consumer<?, ?> consumer;
    private MessageListenerContainer container;
    private DefaultErrorHandler handler;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        avroTemplate = mock(KafkaTemplate.class);
        bytesTemplate = mock(KafkaTemplate.class);
        stubTemplate(avroTemplate);
        stubTemplate(bytesTemplate);

        consumer = mock(Consumer.class);
        container = mock(MessageListenerContainer.class); // isRunning()=false → backoff sleeps abort fast

        handler = new KafkaErrorConfig().dispatchErrorHandler(avroTemplate, bytesTemplate);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void stubTemplate(KafkaTemplate template) {
        ProducerFactory pf = mock(ProducerFactory.class);
        when(pf.getConfigurationProperties()).thenReturn(Map.of());
        when(template.getProducerFactory()).thenReturn(pf);
        when(template.partitionsFor(DLT)).thenReturn(
                IntStream.range(0, 6)
                        .mapToObj(i -> new PartitionInfo(DLT, i, null, null, null))
                        .toList());
        when(template.send(any(ProducerRecord.class))).thenAnswer(inv -> {
            ProducerRecord rec = inv.getArgument(0);
            var meta = new RecordMetadata(
                    new TopicPartition(rec.topic(), rec.partition() == null ? 0 : rec.partition()),
                    0L, 0, 0L, 0, 0);
            return CompletableFuture.completedFuture(new SendResult<>(rec, meta));
        });
    }

    private DispatchAction reassign() {
        return DispatchAction.newBuilder()
                .setOrderId("order-42")
                .setAction(DispatchActionType.REASSIGN)
                .setTargetId("driver-2")
                .setRequestedTs(Instant.ofEpochMilli(1_000_000L))
                .build();
    }

    private void handle(Exception ex, ConsumerRecord<?, ?> record) {
        handler.handleRemaining(ex, List.of(record), consumer, container);
    }

    @Test
    void deserializationPoison_skipsRetries_andLandsOnDltViaBytesTemplate() {
        // a record that never deserialized only exists as raw bytes
        var record = new ConsumerRecord<String, Object>(SOURCE_TOPIC, 3, 0L, "order-42", "garbage".getBytes());
        var poison = new DeserializationException("bad magic byte", "garbage".getBytes(), false, null);

        try {
            handle(poison, record);
        }
        catch (KafkaException e) {
            // recovered-first-record semantics differ across versions; retries are asserted below either way
        }

        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(bytesTemplate).send(captor.capture());
        verify(avroTemplate, never()).send(any(ProducerRecord.class));

        ProducerRecord<?, ?> dead = captor.getValue();
        assertThat(dead.topic()).isEqualTo(DLT);
        assertThat(dead.key()).isEqualTo("order-42"); // key preserved → broker re-partitions by orderId
        assertThat(dead.value()).isInstanceOf(byte[].class);
    }

    @Test
    void transientFailure_retriesThreeTimes_thenDeadLettersViaAvroTemplate() {
        var record = new ConsumerRecord<String, Object>(SOURCE_TOPIC, 1, 7L, "order-42", reassign());
        var transientBoom = new RuntimeException("routing hiccup");

        // original attempt + 3 backoff retries: each non-final failure seeks and rethrows
        for (int attempt = 1; attempt <= 3; attempt++) {
            // spring-kafka 3.3 rethrows a (package-private) RecordInRetryException while retries remain
            assertThatThrownBy(() -> handle(transientBoom, record))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("Record in retry");
            verify(avroTemplate, never()).send(any(ProducerRecord.class));
        }

        // 4th failure: backoff exhausted → recover to DLT
        try {
            handle(transientBoom, record);
        }
        catch (KafkaException e) {
            // see above
        }

        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(avroTemplate).send(captor.capture());
        verify(bytesTemplate, never()).send(any(ProducerRecord.class));

        ProducerRecord<?, ?> dead = captor.getValue();
        assertThat(dead.topic()).isEqualTo(DLT);
        assertThat(dead.key()).isEqualTo("order-42");
        assertThat(dead.value()).isEqualTo(reassign()); // the real object, re-serialized by the Avro template
    }

    @Test
    void illegalArgument_isClassifiedFatal_deadLettersImmediately() {
        var record = new ConsumerRecord<String, Object>(SOURCE_TOPIC, 5, 0L, "order-42", reassign());

        try {
            handle(new IllegalArgumentException("semantically poisonous"), record);
        }
        catch (KafkaException e) {
            // see above
        }

        var captor = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(avroTemplate).send(captor.capture());
        verify(bytesTemplate, never()).send(any(ProducerRecord.class));
        assertThat(captor.getValue().topic()).isEqualTo(DLT);
    }
}
