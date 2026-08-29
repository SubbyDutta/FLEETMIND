package com;

import com.fleetmind.avro.MovementAgg;
import fleetmind.events.AlertEvent;
import fleetmind.events.AlertType;
import fleetmind.events.Severity;
import org.apache.kafka.streams.kstream.Windowed;

import java.time.Instant;
import java.util.UUID;

public class IdleDetector {

    private static final int MIN_PINGS = 10;

    private static final double MAX_MOVEMENT = 20; // meters

    public static boolean isIdle(MovementAgg agg) {

        return !agg.getAssigned()


                &&

                agg.getPingCount() >= MIN_PINGS

                &&

                MovementAggUtil.metersMoved(agg) < MAX_MOVEMENT;

    }

    public static AlertEvent buildAlert(
            Windowed<String> key,
            MovementAgg agg
    ) {

        AlertEvent alert = new AlertEvent();

        alert.setAlertId(UUID.randomUUID().toString());

        alert.setDriverId(key.key());

        alert.setOrderId(null);

        alert.setType(AlertType.IDLE_DRIVER);

        alert.setSeverity(Severity.LOW);

        alert.setReason(
                "Driver remained idle for entire window."
        );

        alert.setWindowStartTs(
                Instant.ofEpochMilli(
                        key.window().start()
                )
        );

        alert.setWindowEndTs(
                Instant.ofEpochMilli(
                        key.window().end()
                )
        );



        return alert;
    }
}