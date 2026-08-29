# Escalation Matrix (RB-ESCALATE)

## Support tiers

| Tier | Who | Scope | Expected response time |
|---|---|---|---|
| TIER-1 | Dispatcher on duty (human or dispatch assistant) | Routine alerts: single stuck driver, single SLA breach, idle repositioning, standard reassignments, SLA-CREDIT-10/20 | Immediate, within 5 minutes of alert |
| TIER-2 | Shift supervisor | Repeat incidents, SLA-CREDIT-50/100 approvals, order cancellations (FLAG_CANCEL), driver conduct reviews, no-idle-driver deadlocks, third-reassignment decisions | Within 15 minutes of page |
| TIER-3 | Operations manager | Fleet-wide outages, systemic incidents affecting 3+ drivers simultaneously, restaurant partner disputes, anything involving rider safety | Within 30 minutes, any hour |

## Severity mapping — how alert severity translates to action

The platform emits three alert types with fixed severities:

- **STUCK** — severity HIGH. Assigned driver moved under 50 meters in 8 minutes. TIER-1 handles per the Stuck Driver Protocol; escalate to TIER-2 on the driver's third stuck incident in a shift or on confirmed breakdown.
- **SLA_BREACH** — severity HIGH. Predicted arrival past the order deadline. TIER-1 handles per the SLA and Credit Policy; escalate to TIER-2 when a credit above 20% is warranted or when mitigation is impossible (no idle drivers).
- **IDLE_DRIVER** — severity LOW. Unassigned driver stationary for a full 5-minute window. TIER-1 housekeeping per the Idle Driver Policy; never a page-worthy event on its own.

## Mandatory TIER-2 escalation triggers

Escalate to the shift supervisor, without discretion, when any of the following occur:

1. An order requires a **third** reassignment.
2. A driver is confirmed in a road accident or reports feeling unsafe — this simultaneously pages TIER-3 if injury is involved.
3. Any single order is predicted more than 40 minutes late (SLA-CREDIT-100 territory).
4. Every driver in the fleet is simultaneously busy or stuck and one or more orders cannot be assigned or reassigned for over 10 minutes.
5. A restaurant refuses to release an order or disputes an order's contents.
6. The same driver triggers 3 or more STUCK alerts within one shift (conduct review).

## Mandatory TIER-3 escalation triggers

1. Three or more drivers flagged STUCK inside the same 15-minute span — assume a citywide traffic event, road closure, or weather incident (Kolkata monsoon flooding is the classic case) and switch to incident mode: pause proactive per-order handling, issue a fleet-wide customer notification, widen all customer-facing ETAs.
2. The event pipeline itself is degraded — alerts arriving with implausible timestamps, ETAs frozen, or the live map stale for more than 2 minutes.
3. Any incident involving rider injury, police involvement, or a customer safety complaint.

## Escalation hygiene

- Escalate with data, not adjectives: order id, driver id, alert history, current ETA, actions already taken. "driver-7 stuck 14 min at Arsalan, order-a1b2c3d4 predicted 12 min late, one reassignment attempted, no idle drivers" is a complete escalation.
- One incident, one escalation thread. Do not re-page for updates on the same incident; append to the existing thread.
- De-escalate explicitly: when the situation resolves, close the thread with the outcome and root cause so the weekly review has clean data.
