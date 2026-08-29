package com;
import com.fleetmind.avro.MovementAgg;
import fleetmind.events.DriverState;
import fleetmind.events.GpsPing;


public final class MovementAggUtil {

    private MovementAggUtil() {}

    public static MovementAgg empty() {

        MovementAgg agg = new MovementAgg();

        agg.setDriverId("");

        agg.setFirstLat(0);

        agg.setFirstLng(0);

        agg.setLastLat(0);

        agg.setLastLng(0);

        agg.setPingCount(0);

        agg.setAssigned(false);



        return agg;
    }

    public static MovementAgg add(
            MovementAgg agg,
            GpsPing ping
    ) {

        if (agg.getPingCount() == 0) {

            agg.setFirstLat(ping.getLat());

            agg.setFirstLng(ping.getLng());

        }

        agg.setLastLat(ping.getLat());

        agg.setLastLng(ping.getLng());

        agg.setDriverId(ping.getDriverId());

        agg.setPingCount(
                agg.getPingCount() + 1
        );



        if (ping.getStatus() == DriverState.TO_PICKUP
                || ping.getStatus() == DriverState.TO_DROP) {

            agg.setAssigned(true);

        }

        return agg;

    }

    public static double metersMoved(
            MovementAgg agg
    ) {

        return EtaCalculator.haversineKm(

                agg.getFirstLat(),
                agg.getFirstLng(),

                agg.getLastLat(),
                agg.getLastLng()

        ) * 1000;

    }

}