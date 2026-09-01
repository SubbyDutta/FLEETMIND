package com;

import com.fleetmind.agent.AgentServiceGrpc;
import com.fleetmind.agent.ChatEvent;
import com.fleetmind.agent.ChatRequest;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

@Component
public class AgentGateway {

    @GrpcClient("ai-service")
    private AgentServiceGrpc.AgentServiceBlockingStub agentStub;

    @CircuitBreaker(name = "aiAgent", fallbackMethod = "agentFallback")
    public void chat(String question, Consumer<ChatEvent> onEvent) {
        Iterator<ChatEvent> events = agentStub
                .withDeadlineAfter(90, TimeUnit.SECONDS)
                .chat(ChatRequest.newBuilder().setQuestion(question).build());
        while (events.hasNext()) {
            onEvent.accept(events.next());
        }
    }

    @CircuitBreaker(name = "aiAgent", fallbackMethod = "agentFallback")
    public void analytics(String question, Consumer<ChatEvent> onEvent) {
        Iterator<ChatEvent> events = agentStub
                .withDeadlineAfter(60, TimeUnit.SECONDS)
                .analytics(ChatRequest.newBuilder().setQuestion(question).build());
        while (events.hasNext()) {
            onEvent.accept(events.next());
        }
    }

    private void agentFallback(String question, Consumer<ChatEvent> onEvent, CallNotPermittedException e) {
        throw new AgentUnavailableException(
                "agent unavailable — circuit is OPEN after repeated ai-service failures; not attempting the call",
                e, true);
    }

    private void agentFallback(String question, Consumer<ChatEvent> onEvent, Throwable t) {
        throw new AgentUnavailableException(
                "agent unavailable — ai-service call failed: " + t.getMessage(), t, false);
    }
}
