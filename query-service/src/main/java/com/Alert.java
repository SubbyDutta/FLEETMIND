package com;

public record Alert(
        long id,
        String type,
        String severity,
        String orderId,
        String driverId,
        String reason,
        boolean resolved,
        String createdAt
) {}
