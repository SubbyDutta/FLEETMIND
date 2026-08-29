# ETA Model and Live Monitoring Guide (RB-ETA)

## How the live ETA is computed

For every active order, the stream processor recomputes the ETA **on every GPS ping from the assigned driver — every 3 seconds**:

1. Take the driver's latest position.
2. Compute the straight-line (haversine) distance from that position to the order's drop-off point.
3. Divide by an assumed city average speed of **25 km/h** and convert to minutes.

The result is published as an ETA update carrying the order id, driver id, ETA in minutes, remaining meters, and the computation timestamp. The live ETA for any order can be read from the state store at any time.

## Known limitations — read before trusting a number

- **Straight-line, not road distance.** The live ETA measures as-the-crow-flies to the drop-off. Real roads add 30–50% in central Kolkata, but the conservative 25 km/h assumption partially compensates (riders actually average closer to 40 km/h when moving). Net effect: the ETA is usually slightly pessimistic on open roads and optimistic in dense gridlock.
- **It ignores the pickup leg's remaining work.** While the driver is still heading to the restaurant (TO_PICKUP), the ETA measures distance to the *drop-off*, not driver→restaurant→drop-off. ETAs during the pickup leg understate true delivery time; treat them as directional until the driver has picked up.
- **A stationary driver freezes the ETA.** If the driver stops, remaining distance stops shrinking and the ETA flatlines. A flat ETA over several minutes is itself a stuck signal — the STUCK detector will confirm it formally within its 8-minute window.
- **Initial estimates use real routing.** The order's original estimated distance and duration (set at creation) come from road routing across both legs when the routing engine is up, falling back to haversine × 1.4 at 25 km/h when it is not. So the *creation-time* estimate and the *live* ETA are built differently — do not expect them to match exactly.

## How to read ETA patterns

| Pattern | Interpretation | Action |
|---|---|---|
| ETA shrinking steadily | Delivery on track | None |
| ETA flat, order assigned | Driver stationary — restaurant wait or traffic | Watch for STUCK alert; check position vs. restaurant |
| ETA jumped sharply upward | Order was reassigned (new driver starts from farther away) | Expected post-reassignment; verify it resumes shrinking |
| ETA shrinking but predicted arrival still past SLA deadline | Late but recovering | If 5+ min late predicted, send DELAY notification; consider PRIORITIZE |
| ETA frozen fleet-wide | Pipeline problem, not a driver problem | Escalate TIER-3 — see Escalation Matrix |

## The PRIORITIZE action

PRIORITIZE is the lightest-touch dispatch action: it marks an order for preferential treatment — first claim on the next idle driver, top placement on the dispatcher's board — without changing its assignment. Use it when an order is at risk but reassignment is unavailable (no idle drivers) or unjustified (driver moving, just slow). It is the standard companion to a DELAY notification.

## Quick reference — the numbers that matter

- GPS ping cadence: every 3 seconds per driver.
- ETA recompute: every ping, i.e. every 3 seconds per active order.
- Assumed speed in the ETA model: 25 km/h.
- SLA deadline: order creation + 1.5 × the creation-time duration estimate.
- SLA_BREACH fires when: ETA computation time + ETA minutes > SLA deadline. Severity HIGH, predictive.
- New orders enter the system roughly every 90 seconds, one per available idle driver.
