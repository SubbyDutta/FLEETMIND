package com;

public record Driver(
        String id,
        String name,
        String status,
        GeoPoint location,
        Double speedKmph
) {}
