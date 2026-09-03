package com;

import com.model.Driver;
import fleetmind.events.DriverState;
import fleetmind.events.OrderEvent;
import fleetmind.events.OrderStatus;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;


import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
public class StartupRehydrator {
    private static final Logger log=LoggerFactory.getLogger(StartupRehydrator.class);
    private final KafkaTemplate<String,OrderEvent> orderEventKafkaTemplate;
    private final DriverRegistry driverRegistry;
    private final RoutingClient routingClient;
    private final SimulationReadiness readiness;
    private final KafkaListenerEndpointRegistry listenerRegistry;

    @Value("${fleetmind.auth.service-email:svc-simulator@fleetmind.internal}")
    private String serviceEmail;

    @Value("${fleetmind.auth.service-password:demo123}")
    private String servicePassword;

    @EventListener(ApplicationReadyEvent.class)
    public void recover(){
        SimpleClientHttpRequestFactory factory= new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);

        RestClient http=RestClient.builder()
                .baseUrl("http://localhost:8086")
                .requestFactory(factory)
                .build();
        String token=obtainServiceToken(http);
        if(token==null)
        {
            log.warn("Rehydration skipped: could not obtain service token from command-service");
            return;
        }
        List<Map<String,Object>> orders=fetchList(http,token,"/api/orders");
        List<Map<String,Object>> drivers=fetchList(http,token,"/api/drivers");
        if(orders == null || drivers == null)
        {
            log.warn("Rehydration skipped: command-service unreachable");
            return;
        }
        int resumed=0;
        Set<String> resumedOrderIds = new HashSet<String>();
        for(Map<String,Object> driverRow : drivers)
        {
            String dbStatus= stringOf(driverRow.get("status"));
            if (!"TO_PICKUP".equals(dbStatus) && !"TO_DROP".equals(dbStatus)) {
                continue;
            }
            Map<String, Object> order = newestOrderFor(stringOf(driverRow.get("id")), orders);
            if (order == null) {
                continue;
            }
            resumeDriver(driverRow,order,dbStatus);
            resumedOrderIds.add(stringOf(order.get("id")));
            resumed++;

        }

        // reap every other active orders cus
        int reaped=0;
        for(Map<String,Object> order : orders)
        {
            String id = stringOf(order.get("id"));
            if (!resumedOrderIds.contains(id)) {
                orderEventKafkaTemplate.send("orders", id, null);   // tombstone -> KTable drop + markDelivered
                reaped++;
            }
        }

        log.info("Rehydration: resumed {} order(s), reaped {} orphan(s)", resumed, reaped);
        readiness.markReady();

        // snapshot first, then stream: only attach to dispatch.actions once the
        // DB snapshot is applied, so a replayed action can't be stomped by rehydration
        listenerRegistry.getListenerContainer("dispatchActions").start();
        log.info("dispatch.actions listener started");


    }

private String obtainServiceToken(RestClient http)
{
    ParameterizedTypeReference<Map<String,Object>> mapType=new ParameterizedTypeReference<Map<String, Object>>() {};
    // retry forever: nothing can be simulated until command-service is up, so giving up
    // here would leave the simulator silent for its whole lifetime (readiness never set)
    int attempt=0;
    while(true){
        attempt++;
        try{
            Map<String,Object> resp=http.post().uri("/api/auth/login")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("email",serviceEmail,"password",servicePassword))
                    .retrieve().body(mapType);
            if(resp!=null && resp.get("token")!=null)
            {
                return (String) resp.get("token");
            }
        }catch(Exception e)
        {
            log.warn("Command-service login not ready (attempt {}): {}",attempt,e.getMessage());
        }
        try{
            Thread.sleep(2000);
        }catch(InterruptedException ef)
        {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}

private List<Map<String,Object>> fetchList(RestClient http,String token,String uri)
{
    ParameterizedTypeReference<List<Map<String,Object>>> listType=new ParameterizedTypeReference<List<Map<String, Object>>>() {};
    int attempt =0;
    while(true){
        attempt++;
                try{
                    return http.get().uri(uri)
                            .header(HttpHeaders.AUTHORIZATION,"Bearer "+token)
                            .retrieve().body(listType);
                }catch(Exception e)
                {
                    log.warn("{} not ready (attempt {}): {}",uri,attempt,e.getMessage());
                    try{
                        Thread.sleep(2000);
                    }catch(InterruptedException ef)
                    {
                        Thread.currentThread().interrupt();
                        return null;
                    }
                }
    }
}
    private String stringOf(Object value) {
        return (value == null) ? "" : value.toString();
    }
    private Map<String,Object> newestOrderFor(String driverId,List<Map<String,Object>> orders){
        Map<String,Object> best=null;
        String bestTs=null;
        for(Map<String,Object> order:orders)
        {
            if (!driverId.equals(stringOf(order.get("assigned_driver")))) {
                continue;
            }
            String ts= stringOf(order.get("updated_at"));
            if(best==null || ts.compareTo(bestTs)>0)
            {
                best = order;
                bestTs = ts;
            }
        }
        return best;
    }
    private void resumeDriver(Map<String,Object> driverRow,Map<String,Object> orderRow,String dbStatus)
    {
        String driverId = stringOf(driverRow.get("id"));
        Driver driver = driverRegistry.getDriver(driverId);
        if (driver == null) {
            return;
        }
        double driverLat =doubleOf(driverRow.get("lat"));
        double driverLng=doubleOf(driverRow.get("lng"));
        double targetLat;
        double targetLng;
        DriverState status;
        if ("TO_PICKUP".equals(dbStatus)) {
            targetLat = doubleOf(orderRow.get("pickup_lat"));
            targetLng = doubleOf(orderRow.get("pickup_lng"));
            status = DriverState.TO_PICKUP;
        } else {
            targetLat = doubleOf(orderRow.get("dropoff_lat"));
            targetLng = doubleOf(orderRow.get("dropoff_lng"));
            status = DriverState.TO_DROP;
        }
        driver.setCurrentLatitude(driverLat);
        driver.setCurrentLongitude(driverLng);
        driver.setStatus(status);
        driver.setStuck(false);
        driver.setCurrentOrder(buildOrderEvent(orderRow, driverId));
        driver.setTargetLatitude(targetLat);
        driver.setTargetLongitude(targetLng);
        Optional<RoutingClient.Route> routed = routingClient.route(driverLat, driverLng, targetLat, targetLng);
        if (routed.isPresent()) {
            driver.setRoute(routed.get().waypoints());
            driver.setRouteIndex(0);
        } else {
            driver.setRoute(null);
            driver.setRouteIndex(0);
        }
        driverRegistry.saveDriver(driver);

    }
    private double doubleOf(Object value) {
        return ((Number) value).doubleValue();
    }
    private OrderEvent buildOrderEvent(Map<String, Object> row, String driverId) {

        return OrderEvent.newBuilder()
                .setOrderId(stringOf(row.get("id")))
                .setCustomerName(stringOf(row.get("customer_name")))
                .setRestaurantId(0L)
                .setRestaurantName(stringOf(row.get("restaurant")))
                .setPickupLat(doubleOf(row.get("pickup_lat")))
                .setPickupLng(doubleOf(row.get("pickup_lng")))
                .setDropoffLat(doubleOf(row.get("dropoff_lat")))
                .setDropoffLng(doubleOf(row.get("dropoff_lng")))
                .setStatus(OrderStatus.ASSIGNED)
                .setAssignedDriverId(driverId)
                .setSlaDeadLineTs(Instant.now())
                .setCreatedTs(Instant.now())
                .setEstimatedDistanceKm(0.0)
                .setEstimatedDurationMinutes(0)
                .build();
    }

}
