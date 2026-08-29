# Fleet Operations Overview (RB-FLEET)

## The fleet

FleetMind operates a **12-rider fleet** in central Kolkata: driver-1 John, driver-2 Alex, driver-3 Mike, driver-4 David, driver-5 Chris, driver-6 Sam, driver-7 Tom, driver-8 Raj, driver-9 Leo, driver-10 Max, driver-11 Eve, and driver-12 Zoe. Riders start each shift staged around the city core (roughly the BBD Bagh–Park Street belt) and carry **one order at a time** — there is no batching or multi-drop in current operations.

## Driver lifecycle

A rider cycles through three states:

1. **IDLE** — no order, available for assignment. Governed by the Idle Driver Policy.
2. **TO_PICKUP** — assigned, riding to the restaurant. The order status is ASSIGNED.
3. **TO_DROP** — food collected, riding to the customer.

On delivery the rider returns to IDLE and re-enters the assignment pool. Assignment currently selects an available idle, non-stuck rider; reassignments specifically target the **nearest** idle rider to the pickup (see the Reassignment Policy).

## Order flow and cadence

- New orders enter the system roughly **every 90 seconds**, one at a time, and only when an idle rider exists to take them — there is no unassigned-order backlog under normal operation.
- Order statuses progress CREATED → ASSIGNED → PICKED_UP → DELIVERED, with CANCELLED as the terminal failure state (see the Cancellation Policy).
- Each order records at creation: restaurant and customer, pickup and drop-off coordinates, estimated distance (km) and duration (minutes) across both legs, and an SLA deadline of 1.5 × the duration estimate.

## Restaurant partners

Ten partner restaurants, all real central-Kolkata institutions, cluster in three zones:

- **Park Street cluster**: Peter Cat, Mocambo, Flurys, Trincas — four pickups within a block of each other; the highest-volume zone.
- **South-east corridor**: Arsalan (Park Circus), 6 Ballygunge Place, Bhojohori Manna, Oh! Calcutta.
- **North / Esplanade pocket**: Aminia and Nizam's near the New Market area.

Drop-offs scatter across the central-south residential belt (roughly the zone bounded by latitude 22.50–22.60, longitude 88.34–88.40).

## Telemetry — what the platform sees

- Every rider emits a **GPS ping every 3 seconds**: position, speed, and current state. Pings flow whether the rider is moving or not — a stationary rider reports speed 0.
- Live ETAs recompute on every ping for every active order (see the ETA Model and Live Monitoring Guide).
- Three automated alert types watch the stream: **STUCK** (assigned rider, under 50 m of movement across an 8-minute window, severity HIGH), **IDLE_DRIVER** (unassigned rider, under 20 m across a 5-minute window, severity LOW), and **SLA_BREACH** (predicted arrival past deadline, severity HIGH). Each has a dedicated runbook.

## Dispatcher toolkit — the four actions

Everything a dispatcher (or the dispatch assistant) does to change the world reduces to four auditable actions, all routed through the transactional outbox:

| Action | Effect | Governing runbook |
|---|---|---|
| REASSIGN | Move an order to a named replacement rider | Reassignment Policy |
| NOTIFY | Send the customer a templated message | Customer Notification Playbook |
| PRIORITIZE | Flag an order for first claim on the next free rider | ETA Model and Live Monitoring Guide |
| FLAG_CANCEL | Cancel the order (TIER-2 approval) | Order Cancellation Policy |

Reading data — positions, ETAs, alerts, order status — is always safe and requires no approval. Writing (the four actions above) follows the authority rules in the Escalation Matrix.

## Simulation and drills

For training and demo purposes, an incident can be injected on demand: freezing a named rider mid-delivery reproduces the full stuck-driver flow end to end (stationary pings → STUCK alert after the 8-minute window → dispatcher response), and the rider can be recovered afterwards, returning to normal movement. Drills should follow the same runbooks as real incidents — that is the point of running them.
