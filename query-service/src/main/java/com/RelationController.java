package com;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.BatchMapping;
import org.springframework.stereotype.Controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Controller
@RequiredArgsConstructor
public class RelationController {

    private final DriverQueryRepository drivers;
    private final OrderQueryRepository orders;
    private final AlertQueryRepository alerts;

    @BatchMapping(typeName = "Order", field = "driver")
    public Map<Order, Driver> driver(List<Order> orderList) {
        Set<String> ids = orderList.stream()
                .map(Order::assignedDriver)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<String, Driver> byId = ids.isEmpty() ? Map.of()
                : drivers.findByIds(ids).stream()
                         .collect(Collectors.toMap(Driver::id, Function.identity()));

        Map<Order, Driver> out = new HashMap<>();
        for (Order o : orderList) {
            String driverId = o.assignedDriver();
            if (driverId != null && byId.containsKey(driverId)) {
                out.put(o, byId.get(driverId));
            }
        }
        return out;
    }

    @BatchMapping(typeName = "Order", field = "alerts")
    public Map<Order, List<Alert>> alerts(List<Order> orderList) {
        Set<String> ids = orderList.stream().map(Order::id).collect(Collectors.toSet());
        Map<String, List<Alert>> byOrder = ids.isEmpty() ? Map.of()
                : alerts.findOpenByOrderIds(ids).stream()
                        .collect(Collectors.groupingBy(Alert::orderId));

        Map<Order, List<Alert>> out = new HashMap<>();
        for (Order o : orderList) {
            out.put(o, byOrder.getOrDefault(o.id(), List.of()));
        }
        return out;
    }

    @BatchMapping(typeName = "Driver", field = "activeOrders")
    public Map<Driver, List<Order>> activeOrders(List<Driver> driverList) {
        Set<String> ids = driverList.stream().map(Driver::id).collect(Collectors.toSet());
        Map<String, List<Order>> byDriver = ids.isEmpty() ? Map.of()
                : orders.findActiveByDriverIds(ids).stream()
                        .collect(Collectors.groupingBy(Order::assignedDriver));

        Map<Driver, List<Order>> out = new HashMap<>();
        for (Driver d : driverList) {
            out.put(d, byDriver.getOrDefault(d.id(), List.of()));
        }
        return out;
    }
}
