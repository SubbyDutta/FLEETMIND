package com;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fleetmind.agent.AgentServiceGrpc;
import com.fleetmind.agent.ChatEvent;
import com.fleetmind.agent.ChatRequest;
import com.fleetmind.status.AgentDiagnosticsGrpc;
import com.fleetmind.status.StatusResponse;
import com.google.protobuf.Empty;
import net.devh.boot.grpc.client.inject.GrpcClient;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

@RestController
@RequestMapping("/api/agent")
@CrossOrigin(origins = "*")
public class AgentController {


    @GrpcClient("ai-service")
    private AgentServiceGrpc.AgentServiceBlockingStub agentStub;

    @GrpcClient("ai-service")
    private AgentDiagnosticsGrpc.AgentDiagnosticsBlockingStub diagnostics;

    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private final ObjectMapper mapper = new ObjectMapper();

    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestParam String q) {
        SseEmitter emitter = new SseEmitter(120_000L);
        executor.submit(() -> {
            try {
                Iterator<ChatEvent> events = agentStub
                        .withDeadlineAfter(90, TimeUnit.SECONDS)  // agent hangs -> clean SSE error
                        .chat(ChatRequest.newBuilder().setQuestion(q).build());
                while (events.hasNext()) {
                    ChatEvent ev = events.next();
                    ObjectNode body = mapper.createObjectNode()
                            .put("step", ev.getStep())
                            .put("tool", ev.getToolName());
                    body.set("payload", mapper.readTree(ev.getPayloadJson()));
                    emitter.send(SseEmitter.event()
                            .name(ev.getType().toLowerCase())  // tool_call / tool_result / final / error
                            .data(body.toString()));
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
        try {
            StatusResponse resp = diagnostics
                    .withDeadlineAfter(3, TimeUnit.SECONDS)
                    .status(Empty.getDefaultInstance());
            return Map.of(
                    "online", true,
                    "model", resp.getModelName(),
                    "tools", resp.getRegisteredToolsList(),
                    "database_alive", resp.getDatabaseAlive());
        } catch (Exception e) {
            return Map.of("online", false, "error", e.getMessage());
        }
    }

}
