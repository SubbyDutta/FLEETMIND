# Stuck Driver Protocol (RB-STUCK)

## What "stuck" means in FleetMind

A driver is classified as STUCK when all three of the following hold inside a single detection window:

1. The driver is currently **assigned** to an active order (state TO_PICKUP or TO_DROP).
2. The platform has received at least **5 GPS pings** from the driver in the window (pings arrive every 3 seconds, so this is a data-quality guard, not a time guard).
3. The driver has moved **less than 50 meters** in total over the last **8 minutes**.

The detection runs on an 8-minute hopping window that advances every 1 minute with a 1-minute grace period for late events. Because alerts are suppressed until a window fully closes, expect a STUCK alert to appear roughly **9 to 10 minutes after the driver actually stopped moving**. This delay is by design: it filters out red lights, short pickups queues, and parking maneuvers. Do not treat the alert timestamp as the moment the problem started — the driver has already been stationary for at least 8 minutes when you see it.

A STUCK alert always carries severity **HIGH** and includes the measured meters moved in its reason text, e.g. "Driver moved only 12 meters while assigned."

## Common causes, in order of likelihood

- Heavy traffic congestion (Park Street, Esplanade and the central restaurant cluster are the usual suspects during lunch and dinner peaks).
- Vehicle breakdown or puncture.
- Long wait at the restaurant — food not ready. Note this still counts as stuck because the driver is stationary while assigned; check whether the driver's position matches the pickup restaurant location before assuming a road problem.
- Driver on an unplanned break or unreachable.

## Diagnosis steps (do these before acting)

1. Pull the driver's current position and compare it against the order's pickup coordinates. If the driver is sitting **at the restaurant**, this is a restaurant delay, not a traffic incident — see the escalation matrix for restaurant-side handling and do NOT reassign immediately.
2. Check the live ETA for the affected order. ETAs are recalculated on every GPS ping (every 3 seconds) assuming a 25 km/h average city speed over the straight-line remaining distance.
3. Check whether an SLA_BREACH alert has also fired for the same order. A STUCK alert plus an SLA_BREACH alert on the same order is the strongest possible signal to act now.

## Action ladder

- **Stuck < 10 minutes** (i.e. the first alert just arrived): monitor. Send the customer a proactive delay notification (NOTIFY action) if the order's SLA deadline is within 15 minutes.
- **Stuck 10–15 minutes** (a second consecutive window fires, or ETA now breaches the SLA deadline): **reassign the order** to the nearest idle driver following the Reassignment Policy. Issue a REASSIGN dispatch action with the replacement driver as target.
- **Driver unreachable or vehicle breakdown confirmed**: reassign immediately regardless of elapsed time, mark the original driver out-of-service, and open a TIER-2 escalation.
- **No idle driver available for reassignment**: apply the PRIORITIZE dispatch action to the affected order, notify the customer of the delay with an honest revised ETA, and escalate to TIER-2 so a supervisor can weigh cancelling via FLAG_CANCEL.

## After the incident

When the driver recovers (movement resumes and pings show speed again), verify their state returns to a normal cycle. A recovered driver who lost their order to reassignment returns to the idle pool automatically and becomes eligible for new assignments on the next order cycle (orders are generated roughly every 90 seconds). Log the root cause: traffic, breakdown, restaurant wait, or driver conduct. Three stuck incidents for the same driver in one shift triggers a TIER-2 conduct review.

## What NOT to do

- Do not reassign on the very first STUCK alert without checking the driver's position first — a driver waiting at the restaurant will simply be replaced by another driver who waits at the same restaurant.
- Do not cancel an order because of a single STUCK alert. Cancellation (FLAG_CANCEL) requires the conditions in the Cancellation Policy.
- Do not notify the customer more than once per 10 minutes about the same stuck incident.
