package com;

import fleetmind.events.EtaUpdate;
import fleetmind.events.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.core.Ordered;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@RequiredArgsConstructor
public class OrderProjection {
    private final OrderRepository orderRepository;
    @KafkaListener(topics = "orders",groupId = "command-service")
    @Transactional
    public void onOrder(ConsumerRecord<String, OrderEvent> record, Acknowledgment ack)
    {
        if(record.value()==null)
        {
            orderRepository.markDelivered(record.key());
        }
        else {
            orderRepository.upsert(record.value());
        }
        ack.acknowledge();

    }

    @KafkaListener(topics = "eta.updates",groupId = "command-service")
    @Transactional
    public void onEta(ConsumerRecord<String, EtaUpdate> record,Acknowledgment ack)
    {
        if(record.value()!=null)
        {
            orderRepository.updateCurrentEta(record.value());
        }
        ack.acknowledge();

    }
}
