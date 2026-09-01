package com;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.http.client.SimpleClientHttpRequestFactory;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

@Component
public class RoutingClient {
    private static final Logger log = LoggerFactory.getLogger(RoutingClient.class);

    private final RestClient http;

    public RoutingClient() {

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(2000);

        this.http = RestClient.builder()
                .baseUrl("http://localhost:5000")
                .requestFactory(factory)
                .build();
    }

    @CircuitBreaker(name="osrm",fallbackMethod="routeFallback")
    public Optional<Route> route(double fromLat, double fromLng, double toLat, double toLng) {
        String uri = String.format(Locale.US,
                "/route/v1/driving/%f,%f;%f,%f?overview=full&geometries=geojson",
                fromLng, fromLat, toLng, toLat);


            OsrmResponse response = http.get().uri(uri).retrieve().body(OsrmResponse.class);

            if (response == null || response.routes == null || response.routes.isEmpty()) {
                log.warn("OSRM returned no route for {},{}->{},{}(response was {})",
                fromLat,fromLng,toLat,toLng,
                response == null? "null":"empty routes");
                return Optional.empty();

            }

            OsrmRoute osrmRoute = response.routes.getFirst();

            List<double[]> waypoints = new ArrayList<>();
            for (List<Double> coordinate : osrmRoute.geometry.coordinates) {
                // each coordinate is [lng, lat] - keep that order
                double lng = coordinate.get(0);
                double lat = coordinate.get(1);
                waypoints.add(new double[]{lng, lat});
            }

            return Optional.of(new Route(osrmRoute.distance, osrmRoute.duration, waypoints));


    }
    // Real failures (timeout, connect-refused, 5xx) land here AFTER being recorded as failures.
    private Optional<Route> routeFallback(double fromLat,double fromLng,double toLat,double toLng,Throwable t)
    {
        log.warn("OSRM routing failed for {},{} -> {},{} : {} — falling back to straight line",
                fromLat, fromLng, toLat, toLng, t.toString());
        return Optional.empty();
    }
    private Optional<Route> routeFallback(double fromLat, double fromLng, double toLat, double toLng,
                                          CallNotPermittedException e) {
        log.debug("OSRM circuit OPEN — instant straight-line fallback");
        return Optional.empty();
    }


    public record Route(double distanceMeters, double durationSeconds, List<double[]> waypoints) {
    }



    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OsrmResponse {
        public List<OsrmRoute> routes;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class OsrmRoute {
        public double distance;
        public double duration;
        public Geometry geometry;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Geometry {
        public List<List<Double>> coordinates;
    }
}
