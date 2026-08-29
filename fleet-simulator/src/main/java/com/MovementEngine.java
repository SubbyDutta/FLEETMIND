package com;

import com.model.Driver;
import fleetmind.events.DriverState;
import fleetmind.events.GpsPing;
import fleetmind.events.OrderEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

@Component
@RequiredArgsConstructor
public class MovementEngine {
private static final double STEP=0.00030;
private final KafkaTemplate<String, GpsPing> kafkaTemplate;
private final KafkaTemplate<String, OrderEvent> orderKafkaTemplate;
private final DriverRegistry driverRegistry;
private final RoutingClient routingClient;
private final SimulationReadiness readiness;
@Scheduled(fixedDelay = 3000)
    public void updateWorld(){
    if(!readiness.isReady())
        return;

    for(Driver driver: driverRegistry.getALLDrivers())
    {

        boolean active = !driver.isStuck() && driver.getStatus() != DriverState.IDLE;
        if(active)
            move(driver);

        publish(driver, active);
        if(active)
            checkArrival(driver);
    }

}
private void move(Driver driver)
{
    List<double[]> route = driver.getRoute();

    // no route (OSRM was down) -> fall back to a straight line toward the target
    if (route == null || route.isEmpty() || driver.getRouteIndex() >= route.size()) {
        driver.setCurrentLatitude(nextCoordinate(driver.getCurrentLatitude(), driver.getTargetLatitude()));
        driver.setCurrentLongitude(nextCoordinate(driver.getCurrentLongitude(), driver.getTargetLongitude()));
        return;
    }

    // step toward the current waypoint (stored as {lng, lat})
    double[] waypoint = route.get(driver.getRouteIndex());
    double waypointLng = waypoint[0];
    double waypointLat = waypoint[1];

    driver.setCurrentLatitude(nextCoordinate(driver.getCurrentLatitude(), waypointLat));
    driver.setCurrentLongitude(nextCoordinate(driver.getCurrentLongitude(), waypointLng));

    // reached this waypoint -> advance to the next one on the next tick
    if (driver.getCurrentLatitude() == waypointLat && driver.getCurrentLongitude() == waypointLng) {
        driver.setRouteIndex(driver.getRouteIndex() + 1);
    }
}
private double nextCoordinate(double current,double target)
{
    double difference= target-current;
    if(Math.abs(difference)<=STEP){
        return target;
    }
    return current+Math.signum(difference)*STEP;
}
private void publish(Driver driver, boolean moving){

    double speedKmph = moving ? (STEP*111000/3.0)*3.6 : 0.0;
    GpsPing ping= GpsPing.newBuilder()
            .setDriverId(driver.getId())
            .setLat(driver.getCurrentLatitude())
            .setLng(driver.getCurrentLongitude())
            .setSpeedKmph(speedKmph)
            .setStatus(driver.getStatus())
            .setTs(Instant.now())
            .build();
    kafkaTemplate.send("gps.pings",driver.getId(),ping);
}
private void checkArrival(Driver driver)
{
    if(driver.getCurrentOrder()==null)
        return;

    List<double[]> route = driver.getRoute();

    // arrived = walked off the end of the route, or (no route) reached the straight-line target
    boolean reached;
    if (route != null && !route.isEmpty()) {
        reached = driver.getRouteIndex() >= route.size();
    } else {
        reached = driver.getCurrentLatitude() == driver.getTargetLatitude()
               && driver.getCurrentLongitude() == driver.getTargetLongitude();
    }

    if (!reached) {
        return;
    }

    switch (driver.getStatus()) {
        case TO_PICKUP -> {
            // reached the restaurant -> now head to the drop-off, and fetch that leg's route
            OrderEvent order = driver.getCurrentOrder();
            driver.setStatus(DriverState.TO_DROP);
            driver.setTargetLatitude(order.getDropoffLat());
            driver.setTargetLongitude(order.getDropoffLng());

            var dropRoute = routingClient.route(driver.getCurrentLatitude(), driver.getCurrentLongitude(),
                    order.getDropoffLat(), order.getDropoffLng());
            if (dropRoute.isPresent()) {
                driver.setRoute(dropRoute.get().waypoints());
                driver.setRouteIndex(0);
            } else {
                driver.setRoute(null);   // no route -> straight-line fallback
                driver.setRouteIndex(0);
            }
        }
        case TO_DROP -> {
            // delivered -> free the driver up
            String orderId=driver.getCurrentOrder().getOrderId();
            orderKafkaTemplate.send("orders",orderId,null);

            driver.setStatus(DriverState.IDLE);
            driver.setCurrentOrder(null);
            driver.setRoute(null);
            driver.setRouteIndex(0);
        }
        default -> {
        }
    }
}





}
