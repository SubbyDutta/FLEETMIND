package com;

import fleetmind.events.*;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

public class SlaBreachDetector {
    public static AlertEvent check(OrderEvent order, EtaUpdate eta)
    {
        Instant computedTs = eta.getComputedTs();
        double etaMinutes = eta.getEtaMinutes();
        long etaSeconds = (long) (etaMinutes * 60);
        Instant predictedArrival = computedTs.plusSeconds(etaSeconds);
        Instant slaDeadline = order.getSlaDeadLineTs();
        if (!predictedArrival.isAfter(slaDeadline)) {
            return null;
        }
        long lateMinutes= Duration.between(slaDeadline,predictedArrival).toMinutes();
        String reason = "ETA " + Math.round(etaMinutes) + " min puts arrival "
                + lateMinutes + " min past SLA deadline";
        return AlertEvent.newBuilder()
                .setAlertId(UUID.randomUUID().toString())
                .setOrderId(order.getOrderId())
                .setDriverId(order.getAssignedDriverId())
                .setType(AlertType.SLA_BREACH)
                .setReason(reason)
                .setSeverity(Severity.HIGH)
                .setWindowStartTs(computedTs)
                .setWindowEndTs(computedTs)
                .build();



    }

}
