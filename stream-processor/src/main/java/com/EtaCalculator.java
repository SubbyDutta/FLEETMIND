package com; // your package

import fleetmind.events.EtaUpdate;
import fleetmind.events.GpsPing;
import fleetmind.events.OrderEvent;

import java.time.Instant;

public class EtaCalculator {

    private static final double SPEED_KMH = 25.0; // city average, constant for now

    public static EtaUpdate calculate(OrderEvent order, GpsPing ping) {
        double remainingKm = haversineKm(
                ping.getLat(), ping.getLng(),
                order.getDropoffLat(), order.getDropoffLng()   // use your actual field names
        );
        double etaMinutes = (remainingKm / SPEED_KMH) * 60.0;

        return EtaUpdate.newBuilder()
                .setOrderId(order.getOrderId())
                .setDriverId(order.getAssignedDriverId())
                .setEtaMinutes(etaMinutes)
                .setRemainingMeters(remainingKm*1000)
                .setComputedTs(ping.getTs())

                .build();
    }

    private static final double EARTH_RADIUS_KM=6371.0;
    public static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return EARTH_RADIUS_KM * c;
    }
}