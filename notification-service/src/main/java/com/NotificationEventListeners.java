package com;

import fleetmind.events.AlertEvent;
import fleetmind.events.DispatchAction;
import fleetmind.events.OrderEvent;
import fleetmind.events.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class NotificationEventListeners {

    private static final String TENANT = "acme";
    private static final Set<OrderStatus> NOTIFIABLE_ORDER_STATUSES =
            Set.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED);

    private final NotificationService notificationService;

    private static String eventId(ConsumerRecord<?, ?> record) {
        return record.topic() + "-" + record.partition() + "-" + record.offset();
    }

    @KafkaListener(topics = "alerts", groupId = "notification-service")
    public void onAlert(ConsumerRecord<String, AlertEvent> record) {
        AlertEvent alert = record.value();
        String subject = "[" + alert.getSeverity() + "] " + alert.getType()
                + " — " + (alert.getOrderId() != null ? alert.getOrderId() : alert.getDriverId());

        Map<String, Object> model = new HashMap<>();
        model.put("type", alert.getType().name());
        model.put("severity", alert.getSeverity().name());
        model.put("orderId", alert.getOrderId() == null ? "-" : alert.getOrderId());
        model.put("driverId", alert.getDriverId() == null ? "-" : alert.getDriverId());
        model.put("reason", alert.getReason());
        model.put("windowStart", alert.getWindowStartTs().toString());
        model.put("windowEnd", alert.getWindowEndTs().toString());

        notificationService.notify(eventId(record), TENANT, "ALERT",
                subject, alert.getReason(), "alert", model);
    }

    @KafkaListener(topics = "orders", groupId = "notification-service")
    public void onOrder(ConsumerRecord<String, OrderEvent> record) {
        OrderEvent order = record.value();
        if (order == null || !NOTIFIABLE_ORDER_STATUSES.contains(order.getStatus())) {
            return;
        }
        String subject = "Order " + order.getOrderId() + " " + order.getStatus();

        Map<String, Object> model = new HashMap<>();
        model.put("orderId", order.getOrderId());
        model.put("status", order.getStatus().name());
        model.put("customerName", order.getCustomerName());
        model.put("restaurantName", order.getRestaurantName());
        model.put("driverId", order.getAssignedDriverId() == null ? "-" : order.getAssignedDriverId());
        model.put("slaDeadline", order.getSlaDeadLineTs().toString());

        notificationService.notify(eventId(record), TENANT, "ORDER_STATUS",
                subject, order.getCustomerName() + "'s order from " + order.getRestaurantName()
                        + " is " + order.getStatus(), "order-status", model);
    }

    @KafkaListener(topics = "dispatch.actions", groupId = "notification-service")
    public void onDispatchAction(ConsumerRecord<String, DispatchAction> record) {
        DispatchAction action = record.value();
        String subject = "Dispatch " + action.getAction() + " on order " + action.getOrderId();

        Map<String, Object> model = new HashMap<>();
        model.put("action", action.getAction().name());
        model.put("orderId", action.getOrderId());
        model.put("targetId", action.getTargetId() == null ? "-" : action.getTargetId());
        model.put("requestedTs", action.getRequestedTs().toString());

        notificationService.notify(eventId(record), TENANT, "DISPATCH_ACTION",
                subject, "The dispatch agent executed " + action.getAction()
                        + " on order " + action.getOrderId(), "dispatch-action", model);
    }
}
