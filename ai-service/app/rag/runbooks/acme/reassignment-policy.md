# Order Reassignment Policy (RB-REASSIGN)

## When reassignment is permitted

An order may be reassigned from its current driver to a replacement driver only when at least one of these conditions is met:

1. The assigned driver has been flagged STUCK for two consecutive detection windows (roughly 10+ minutes without meaningful movement) — see the Stuck Driver Protocol.
2. An SLA_BREACH alert has fired for the order AND the assigned driver's ETA is still worsening or flat (the driver is not recovering).
3. The driver reports a vehicle breakdown, accident, or is otherwise confirmed unable to complete the delivery.
4. A supervisor (TIER-2 or above) explicitly instructs the reassignment.

Reassignment is a **write action** and flows through the transactional outbox as a REASSIGN dispatch action carrying the order id and the replacement driver id as target. It is never performed as a direct database edit.

## How to choose the replacement driver

Select the **nearest idle driver** to the order's pickup location. "Idle" means driver state IDLE, no current order, and not flagged stuck. The fleet has 12 drivers (driver-1 through driver-12); at busy moments most are mid-delivery, so the idle pool can be small or empty.

Selection rules, in priority order:

1. Nearest idle, non-stuck driver by road distance to the pickup point.
2. If two candidates are within 300 meters of each other, prefer the one who has been idle longer (fairness rotation).
3. Never select a driver who is currently the subject of an open STUCK or conduct incident, even if their state reads IDLE.
4. Never reassign an order back to the driver it was just taken from.

## If no idle driver exists

Do not force-assign to a busy driver — a driver carries exactly one order at a time in this fleet. Instead:

- Apply the PRIORITIZE dispatch action so the order is first in line when a driver frees up. Deliveries complete continuously, so the idle pool typically refreshes within a few minutes.
- Send the customer a delay notification with an honest revised ETA.
- If the order's SLA deadline has already passed and no driver will free up within 10 minutes, escalate to TIER-2 to consider FLAG_CANCEL per the Cancellation Policy.

## Limits and guardrails

- **Maximum 2 reassignments per order.** An order bounced across three drivers signals a systemic problem (bad address, unreachable drop zone, restaurant not releasing food); after the second reassignment, escalate to TIER-2 instead of reassigning again.
- Reassignment resets the pickup leg: the replacement driver travels to the restaurant first. Expect the ETA to jump upward immediately after a reassignment and then recover — this is normal and is not itself grounds for another reassignment.
- Every reassignment must be followed by a customer notification (see Customer Notification Playbook) informing them a new rider is on the way.
- The SLA deadline does **not** reset on reassignment. If the new ETA still lands past the original deadline, SLA credit rules apply as normal.

## Audit

Every REASSIGN action is recorded with a requested timestamp in the dispatch actions log. Dispatchers should be able to answer, for any reassignment: which alert triggered it, which candidates were considered, and why the chosen driver won.
