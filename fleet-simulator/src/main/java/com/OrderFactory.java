package com;

import com.model.Driver;
import fleetmind.events.DriverState;
import fleetmind.events.OrderEvent;
import fleetmind.events.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Component
@RequiredArgsConstructor
public class OrderFactory {

    private record Restaurant(String name,Long id, double lat, double lng) {}
    private final RoutingClient routingClient;
    private final SimulationReadiness readiness;
    private static final List<Restaurant> RESTAURANTS = List.of(
            new Restaurant("Peter Cat", 1L,       22.5524, 88.3519),
            new Restaurant("Arsalan", 2L,          22.5392, 88.3673),
            new Restaurant("Mocambo", 3L,           22.5531, 88.3527),
            new Restaurant("Flurys", 4L,           22.5520, 88.3512),
            new Restaurant("6 Ballygunge Place", 5L, 22.5276, 88.3654),
            new Restaurant("Oh! Calcutta", 6L,   22.5365, 88.3535),
            new Restaurant("Bhojohori Manna", 7L,  22.5188, 88.3667),
            new Restaurant("Aminia", 8L,       22.5639, 88.3515),
            new Restaurant("Nizam's", 9L,         22.5648, 88.3502),
            new Restaurant("Trincas", 10L,         22.5527, 88.3531),
            new Restaurant("Kusum Rolls", 11L,     22.5518, 88.3529),
            new Restaurant("Zeeshan", 12L,         22.5399, 88.3664),
            new Restaurant("Kewpie's", 13L,        22.5310, 88.3467),
            new Restaurant("Balwant Singh's", 14L, 22.5352, 88.3450),
            new Restaurant("Golbari", 15L,         22.5960, 88.3721),
            new Restaurant("Mitra Cafe", 16L,      22.5905, 88.3680),
            new Restaurant("Banana Leaf", 17L,     22.5183, 88.3620),
            new Restaurant("Tero Parbon", 18L,     22.5165, 88.3595)
    );
    private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
    private final DriverRegistry driverRegistry;
    private static final String[] CUSTOMERS =
            {"Priya", "Amit", "Sara", "Vikram", "Neha", "Karan", "Divya", "Rahul"};

    @Scheduled(fixedDelay = 150000, initialDelay = 20000)
    public void createOrder()
    {
        if(!readiness.isReady())
            return;
        Driver driver=findIdleDriver();
        if(driver == null)
            return;
        Restaurant r=RESTAURANTS.get(ThreadLocalRandom.current().nextInt(RESTAURANTS.size()));
        double dropLat=ThreadLocalRandom.current().nextDouble(22.50,22.60);
        double dropLng=ThreadLocalRandom.current().nextDouble(88.34,88.40);
        String customer = CUSTOMERS[ThreadLocalRandom.current().nextInt(CUSTOMERS.length)];
        String orderId = "order-" + UUID.randomUUID().toString().substring(0, 8);
        var delivery = routingClient.route(r.lat(), r.lng(), dropLat, dropLng);  // restaurant → drop
        var pick=routingClient.route(driver.getCurrentLatitude(),driver.getCurrentLongitude(),r.lat(),r.lng());
        double roadKm, estMinutes;
        if (delivery.isPresent()&& pick.isPresent()) {
            roadKm     = (delivery.get().distanceMeters() +pick.get().distanceMeters()) / 1000.0;
            estMinutes = (delivery.get().durationSeconds() +pick.get().durationSeconds()) / 60.0;
            driver.setRoute(pick.get().waypoints());
            driver.setRouteIndex(0);

        } else {                                                   // Haversine fallback
            roadKm     = (haversineKm(r.lat(), r.lng(), dropLat, dropLng)+haversineKm(driver.getCurrentLatitude(),driver.getCurrentLongitude(),r.lat(),r.lng())) * 1.4;
            estMinutes = (roadKm / 25.0) * 60.0;
            driver.setRoute(null);   // no route -> MovementEngine moves in a straight line
            driver.setRouteIndex(0);
        }

        OrderEvent order=OrderEvent.newBuilder()
                .setOrderId(orderId)
                .setCustomerName(customer)
                .setRestaurantId(r.id)
                .setRestaurantName(r.name)
                .setPickupLat(r.lat())
                .setPickupLng(r.lng())
                .setDropoffLat(dropLat)
                .setDropoffLng(dropLng)
                .setStatus(OrderStatus.ASSIGNED)
                .setAssignedDriverId(driver.getId())
                .setSlaDeadLineTs(Instant.now().plus(Duration.ofMinutes((long) (estMinutes*1.5))))
                .setCreatedTs(Instant.now())
                .setEstimatedDistanceKm(roadKm)
                .setEstimatedDurationMinutes((int) estMinutes)
                .build();
        driver.setCurrentOrder(order);
        driver.setStatus(DriverState.TO_PICKUP);
        driver.setTargetLatitude(r.lat());
        driver.setTargetLongitude(r.lng());




        kafkaTemplate.send("orders",orderId,order);
    }
    private Driver findIdleDriver(){
        return driverRegistry.getALLDrivers().stream()
                .filter(d->d.getStatus()== DriverState.IDLE&& !d.isStuck())
                .findFirst()
                .orElse(null);
    }

        private static final double EARTH_RADIUS_KM=6371.0;
        public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
            double dLat = Math.toRadians(lat2 - lat1);
            double dLng = Math.toRadians(lng2 - lng1);
            double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                    + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                    * Math.sin(dLng / 2) * Math.sin(dLng / 2);
            double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
            return EARTH_RADIUS_KM * c;
        }





}
