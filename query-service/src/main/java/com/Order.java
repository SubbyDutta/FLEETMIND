package com;

public record Order(
        String id,
        String customerName,
        String restaurant,
        String status,
        GeoPoint pickup,
        GeoPoint dropoff,
        String assignedDriver,
        String slaDeadline,
        String currentEta,
        String createdAt
) {}
