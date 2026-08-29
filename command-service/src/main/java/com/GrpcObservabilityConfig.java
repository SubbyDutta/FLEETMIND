package com;

import io.micrometer.core.instrument.binder.grpc.ObservationGrpcServerInterceptor;
import io.micrometer.observation.ObservationRegistry;
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor;
import org.springframework.context.annotation.Configuration;

/**
 * net.devh's starter auto-wires gRPC METRICS once a MeterRegistry exists, but has
 * no observation (tracing) support — so ToolService/TelemetryGrpcService calls would
 * be invisible in Jaeger. Micrometer ships a ready-made server interceptor that
 * opens an Observation per RPC (span name = rpc method, e.g. ToolService/Reassign)
 * and continues any incoming W3C trace context from gRPC metadata.
 */
@Configuration
public class GrpcObservabilityConfig {

    @GrpcGlobalServerInterceptor
    ObservationGrpcServerInterceptor observationGrpcServerInterceptor(ObservationRegistry registry) {
        return new ObservationGrpcServerInterceptor(registry);
    }
}
