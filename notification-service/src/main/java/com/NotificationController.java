package com;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationRepository notifications;

    @GetMapping
    public List<Map<String, Object>> recent(
            @RequestParam String email,
            @RequestParam(defaultValue = "50") int limit) {
        return notifications.findRecent(email, Math.min(limit, 200));
    }
}
