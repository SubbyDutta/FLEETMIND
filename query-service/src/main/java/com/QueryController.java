package com;

import lombok.RequiredArgsConstructor;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class QueryController {

    private final OrderQueryRepository orders;
    private final DriverQueryRepository drivers;
    private final AlertQueryRepository alerts;

    @QueryMapping
    public List<Order> activeOrders(@Argument int limit) {
        return orders.findActive(Math.min(limit, 200));
    }

    @QueryMapping
    public Order order(@Argument String id) {
        return orders.findById(id);
    }

    @QueryMapping
    public List<Driver> drivers(@Argument String status) {
        return drivers.findAll(status);
    }

    @QueryMapping
    public Driver driver(@Argument String id) {
        return drivers.findById(id);
    }

    @QueryMapping
    public List<Alert> openAlerts(@Argument int limit) {
        return alerts.findOpen(Math.min(limit, 200));
    }
}
