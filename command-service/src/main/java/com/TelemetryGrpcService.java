package com;

import com.fleetmind.telemetry.DriverPing;
import com.fleetmind.telemetry.TelemetryServiceGrpc;
import com.fleetmind.telemetry.WatchRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import lombok.RequiredArgsConstructor;
import net.devh.boot.grpc.server.service.GrpcService;

import java.util.Map;

@GrpcService
@RequiredArgsConstructor
public class TelemetryGrpcService extends TelemetryServiceGrpc.TelemetryServiceImplBase {
    private final DriverRepository driverRepository;
    @Override
    public void watchDriver(WatchRequest req, StreamObserver<DriverPing> obs)
    {
        // never trust the requested count: this endpoint holds a worker thread ~1s/sample
        int samples = Math.min(Math.max(req.getSamples(), 1), 15);
        for(int i=0;i<samples;i++)
        {
            Map<String,Object> d=driverRepository.findById(req.getDriverId());
            if (d == null) {
                obs.onError(Status.NOT_FOUND
                        .withDescription("driver " + req.getDriverId() + " not found")
                        .asRuntimeException());
                return;                                   // onError ends the stream — nothing after it
            }
            obs.onNext(DriverPing.newBuilder()            // ← one streamed message
                    .setLat(((Number) d.get("lat")).doubleValue())
                    .setLng(((Number) d.get("lng")).doubleValue())
                    .setStatus((String) d.get("status"))
                    .setSpeedKmph(((Number) d.getOrDefault("speed_kmph", 0)).doubleValue())
                    .setTsMillis(System.currentTimeMillis())
                    .build());
            try {
                Thread.sleep(1000);                       // sample interval
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                obs.onError(Status.CANCELLED.asRuntimeException());
                return;
            }
        }
        obs.onCompleted();  //ends the stteam 
    }



}
