package com;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fleetmind.agent.ChatEvent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

@RestController
@RequestMapping("/api/analytics")
@CrossOrigin(origins = "*")
public class AnalyticsController {

    private final AgentGateway agentGateway;
    private final ObjectMapper mapper = new ObjectMapper();

    public AnalyticsController(AgentGateway agentGateway) {
        this.agentGateway = agentGateway;
    }

    public record AnalyticsRequest(String question) {}

    @PostMapping
    public ResponseEntity<Map<String, Object>> ask(@RequestBody AnalyticsRequest req) throws Exception {
        if (req.question() == null || req.question().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "question is required"));
        }
        List<String> toolsUsed = new ArrayList<>();
        AtomicReference<ChatEvent> terminal = new AtomicReference<>();
        try {
            agentGateway.analytics(req.question(), ev -> {
                switch (ev.getType()) {
                    case "TOOL_CALL" -> toolsUsed.add(ev.getToolName());
                    case "FINAL", "ERROR" -> terminal.set(ev);
                }
            });
        } catch (AgentUnavailableException e) {
            return ResponseEntity.status(503).body(Map.of(
                    "error", e.getMessage(),
                    "circuit_open", e.isCircuitOpen()));
        }

        ChatEvent last = terminal.get();
        if (last == null) {
            return ResponseEntity.status(502).body(Map.of("error", "stream ended without a final answer"));
        }
        if ("ERROR".equals(last.getType())) {
            String msg = mapper.readTree(last.getPayloadJson()).path("error").asText();
            return ResponseEntity.status(502).body(Map.of("error", msg, "tools_used", toolsUsed));
        }
        String answer = mapper.readTree(last.getPayloadJson()).path("answer").asText();
        return ResponseEntity.ok(Map.of("answer", answer, "steps", last.getStep(), "tools_used", toolsUsed));
    }
}
