package com;

import com.model.Driver;
import fleetmind.events.DispatchAction;
import fleetmind.events.DriverState;
import fleetmind.events.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class DispatchActionListener {
    private static final Logger log = LoggerFactory.getLogger(DispatchActionListener.class);

    private final DriverRegistry driverRegistry;
    private final RoutingClient routingClient;
    private final KafkaTemplate<String, OrderEvent> orderKafkaTemplate;

    @KafkaListener(id = "dispatchActions", topics = "dispatch.actions",
            containerFactory = "dispatchListenerFactory", autoStartup = "false")
    public void onAction(DispatchAction action) {
        switch (action.getAction()) {
            case REASSIGN -> applyReassign(action);
            case NOTIFY   -> log.info("NOTIFY for order {} (customer-facing, nothing to move)",
                    action.getOrderId());
            default       -> log.warn("unhandled action {} for order {}",
                    action.getAction(), action.getOrderId());
        }
    }

    private void applyReassign(DispatchAction action) {
        String orderId = String.valueOf(action.getOrderId());
        String newDriverId = action.getTargetId() == null ? null : String.valueOf(action.getTargetId());

        Driver newDriver = (newDriverId == null) ? null : driverRegistry.getDriver(newDriverId);
        if (newDriver == null) {
            log.warn("REASSIGN {}: target driver {} unknown to sim — skipping", orderId, newDriverId);
            return;
        }
        // idempotency: at-least-once delivery means we may see this action twice
        if (newDriver.getCurrentOrder() != null
                && orderId.equals(String.valueOf(newDriver.getCurrentOrder().getOrderId()))) {
            log.info("REASSIGN {}: already applied to {} — duplicate, ignoring", orderId, newDriverId);
            return;
        }

        // find whoever holds this order in the sim's world
        Driver oldDriver = driverRegistry.getALLDrivers().stream()
                .filter(d -> d.getCurrentOrder() != null
                        && orderId.equals(String.valueOf(d.getCurrentOrder().getOrderId())))
                .findFirst()
                .orElse(null);
        if (oldDriver == null) {
            log.warn("REASSIGN {}: no sim driver holds this order (stale action?) — skipping", orderId);
            return;
        }

        OrderEvent order = OrderEvent.newBuilder(oldDriver.getCurrentOrder())
                .setAssignedDriverId(newDriverId)
                .build();

        // release the old driver — keep stuck=true (that's WHY they lost the order)
        oldDriver.setStatus(DriverState.IDLE);
        oldDriver.setCurrentOrder(null);
        oldDriver.setRoute(null);
        oldDriver.setRouteIndex(0);
        driverRegistry.saveDriver(oldDriver);

        // new driver heads to the restaurant
        newDriver.setCurrentOrder(order);
        newDriver.setStatus(DriverState.TO_PICKUP);
        newDriver.setTargetLatitude(order.getPickupLat());
        newDriver.setTargetLongitude(order.getPickupLng());
        var route = routingClient.route(newDriver.getCurrentLatitude(), newDriver.getCurrentLongitude(),
                order.getPickupLat(), order.getPickupLng());
        newDriver.setRoute(route.map(RoutingClient.Route::waypoints).orElse(null));
        newDriver.setRouteIndex(0);
        driverRegistry.saveDriver(newDriver);

        // re-emit so the streams world (KTable joins, ETA/SLA) learns the new driver too
        orderKafkaTemplate.send("orders", orderId, order);

        log.info("REASSIGN applied: order {} moved {} -> {}", orderId, oldDriver.getId(), newDriverId);
    }
}