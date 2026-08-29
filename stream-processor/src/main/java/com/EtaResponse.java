package com;

// Plain object just for the REST response.
// We copy the Avro EtaUpdate fields into this so Spring can turn it into JSON cleanly.
public record EtaResponse(
        String orderId,
        String driverId,
        double etaMinutes,
        double remainingMeters,
        String computedTs
) {
}
