package com;

import com.fleetmind.avro.MovementAgg;
import fleetmind.events.AlertEvent;
import fleetmind.events.AlertType;
import fleetmind.events.Severity;
import org.apache.kafka.streams.kstream.Windowed;

import java.time.Instant;
import java.util.UUID;

public class StuckDetector {
    public static final double MAX_METERS=50;
    private static final int MIN_PINGS=5;
    public static boolean isStuck(MovementAgg agg){
        return agg.getAssigned() && agg.getPingCount()>=MIN_PINGS
                && MovementAggUtil.metersMoved(agg)<MAX_METERS;
    }
    public static AlertEvent alertEvent(
            Windowed<String> key,
            MovementAgg agg
    ){
        AlertEvent alert = new AlertEvent();

        alert.setAlertId(UUID.randomUUID().toString());

        alert.setType(AlertType.STUCK);

        alert.setSeverity(Severity.HIGH);

        alert.setDriverId(key.key());

        alert.setOrderId(null);

        alert.setReason(
                "Driver moved only "
                        + Math.round(MovementAggUtil.metersMoved(agg))
                        + " meters while assigned."
        );
        alert.setWindowEndTs(Instant.ofEpochMilli(key.window().end()));
        alert.setWindowStartTs(Instant.ofEpochMilli(key.window().start()));
        return alert;
    }
}
