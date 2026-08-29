package com;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetmind.agent.AgentServiceGrpc;
import com.fleetmind.agent.ChatEvent;
import com.fleetmind.agent.ChatRequest;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "*")
public class AnalyticsController {

    @GrpcClient("ai-service")
    private AgentServiceGrpc.AgentServiceBlockingStub agentStub;

    private final ObjectMapper mapper = new ObjectMapper();

    public record AnalyticsRequest(String question) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> ask(@RequestBody AnalyticsRequest req) throws Exception {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        List<String> toolsUsed = new ArrayList<>();
        try {
            Iterator<ChatEvent> events = agentStub
                    .withDeadlineAfter(60, TimeUnit.SECONDS)
                    .analytics(ChatRequest.newBuilder().setQuestion(req.question()).build());
            while (events.hasNext()) {
                ChatEvent ev = events.next();
                switch (ev.getType()) {
                    case "TOOL_CALL" -> toolsUsed.add(ev.getToolName());
                    case "FINAL" -> {
                        String answer = mapper.readTree(ev.getPayloadJson()).path("answer").asText();
                        return ResponseEntity.ok(Map.of(
                                "answer", answer,
                                "steps", ev.getStep(),
                                "tools_used", toolsUsed));
                    }
                    case "ERROR" -> {
                        String msg = mapper.readTree(ev.getPayloadJson()).path("error").asText();
                        return ResponseEntity.status(502).body(Map.of("error", msg, "tools_used", toolsUsed));
                    }
                }
            }
            return ResponseEntity.status(502).body(Map.of("error", "stream ended without a final answer"));
        } catch (io.grpc.StatusRuntimeException e) {
            return ResponseEntity.status(502).body(Map.of("error", "ai-service: " + e.getStatus()));
        }
    }
}