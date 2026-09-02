package com;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DashboardController {

    private final DriverRepository driverRepository;
    private final OrderRepository orderRepository;
    private final AlertRepository alertRepository;
    private final RoutingClient routingClient;

    @GetMapping("/drivers")
    public List<Map<String, Object>> drivers() {
        return driverRepository.findAll();
    }

    @GetMapping("/orders")
    public List<Map<String, Object>> orders() {
        return orderRepository.findActive();
    }

    @GetMapping("/alerts")
    public List<Map<String, Object>> alerts() {
        return alertRepository.findRecent();
    }
    @GetMapping("drivers/{id}/route")
    public Map<String,Object> driverRoute(@PathVariable String id)
    {
        Map<String,Object> response = new HashMap<>();
        List<double[]> points = new ArrayList<>();
        response.put("points",points);
        Map<String,Object> driver =driverRepository.findById(id);
        if(driver == null)
        {
            return response;
        }
        String status= (String) driver.get("status");
        if (status == null || status.equals("IDLE") || status.equals("OFFLINE")) {
            return response;
        }
        Map<String,Object> order= orderRepository.findActiveByDriver(id);
        if (order == null) {
            return response;
        }
        double driverLat = ((Number) driver.get("lat")).doubleValue();
        double driverLng = ((Number) driver.get("lng")).doubleValue();
        double destLat;
        double destLng;
        if (status.equals("TO_PICKUP")) {
            destLat = ((Number) order.get("pickup_lat")).doubleValue();
            destLng = ((Number) order.get("pickup_lng")).doubleValue();
        } else if (status.equals("TO_DROP")) {
            destLat = ((Number) order.get("dropoff_lat")).doubleValue();
            destLng = ((Number) order.get("dropoff_lng")).doubleValue();
        } else {
            return response;
        }
        Optional<List<double[]>> routed = routingClient.routeLatLng(driverLat, driverLng, destLat, destLng);
        if (routed.isPresent()) {
            points.addAll(routed.get());
        }
        return response;
    }
}