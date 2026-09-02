package com;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetmind.status.AgentDiagnosticsGrpc;
import com.fleetmind.status.StatusResponse;
import com.google.protobuf.Empty;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/agent")
public class AgentController {

    @GrpcClient("ai-service")
    private AgentDiagnosticsGrpc.AgentDiagnosticsBlockingStub diagnostics;

    private final AgentGateway agentGateway;
    private final CircuitBreakerRegistry breakerRegistry;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();

    public AgentController(AgentGateway agentGateway, CircuitBreakerRegistry breakerRegistry) {
        this.agentGateway = agentGateway;
        this.breakerRegistry = breakerRegistry;
    }

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String q) {
        SseEmitter emitter = new SseEmitter(120_000L);
        String tenant = TenantContext.require();
        executor.submit(() -> {
            try {
                agentGateway.chat(q, tenant, ev -> {
                    try {
                        ObjectNode body = mapper.createObjectNode()
                                .put("step", ev.getStep())
                                .put("tool", ev.getToolName());
                        body.set("payload", mapper.readTree(ev.getPayloadJson()));
                        emitter.send(SseEmitter.event()
                                .name(ev.getType().toLowerCase())
                                .data(body.toString()));
                    } catch (Exception e) {
                        throw new UncheckedIOException(new IOException(e));
                    }
                });
                emitter.complete();
            } catch (AgentUnavailableException e) {
                try {
                    emitter.send(SseEmitter.event().name("error").data(mapper.createObjectNode()
                            .put("error", e.getMessage())
                            .put("circuit_open", e.isCircuitOpen()).toString()));
                } catch (Exception ignored) {
                }
                emitter.complete();
            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });
        return emitter;
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        String circuit = breakerRegistry.circuitBreaker("aiAgent").getState().name();
        try {
            StatusResponse resp = diagnostics
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .status(Empty.getDefaultInstance());
            return Map.of(
                    "online", true,
                    "model", resp.getModelName(),
                    "tools", resp.getRegisteredToolsList(),
                    "database_alive", resp.getDatabaseAlive(),
                    "circuit", circuit);
        } catch (Exception e) {
            return Map.of("online", false, "error", e.getMessage(), "circuit", circuit);
        }
    }

}
