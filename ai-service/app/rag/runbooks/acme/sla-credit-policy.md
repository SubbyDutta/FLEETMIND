# SLA and Customer Credit Policy (RB-SLA)

## How the SLA deadline is set

Every order receives its SLA deadline at creation time: **deadline = order creation time + 1.5 × the estimated delivery duration**. The estimated duration covers both legs — driver to restaurant, then restaurant to customer — computed from real road routing when available, or from straight-line distance × 1.4 at an assumed 25 km/h city speed as a fallback. Example: an order estimated at 20 minutes gets a 30-minute SLA window.

## What an SLA_BREACH alert means

The stream processor recomputes each active order's ETA on every driver GPS ping (every 3 seconds) and joins it against the order's deadline. The moment the **predicted arrival time** (ETA computation time + ETA minutes) lands past the SLA deadline, an SLA_BREACH alert fires with severity **HIGH**. The alert reason states how late the arrival is predicted to be, e.g. "ETA 18 min puts arrival 7 min past SLA deadline."

Important: the breach alert is **predictive**, not retrospective. It fires when the order is *forecast* to be late, which is exactly when a dispatcher still has time to fix it — usually by reassignment or prioritization. Treat it as a call to action, not a post-mortem.

Also note the ETA model is conservative: it assumes 25 km/h over the remaining straight-line distance, while riders typically travel faster on open roads. A marginal predicted lateness of 1–2 minutes frequently self-corrects. A predicted lateness of 5+ minutes rarely does.

## Credit tiers

Credits are computed against **actual** lateness at delivery, measured from the SLA deadline to the delivered timestamp. Predicted breaches that self-correct earn no credit.

| Code | Actual lateness | Credit |
|---|---|---|
| SLA-CREDIT-0 | Delivered on or before deadline | No credit |
| SLA-CREDIT-10 | 1–10 minutes late | 10% of order value |
| SLA-CREDIT-20 | 11–20 minutes late | 20% of order value |
| SLA-CREDIT-50 | 21–40 minutes late | 50% of order value |
| SLA-CREDIT-100 | More than 40 minutes late, or order cancelled after food was prepared | 100% refund |

## Approval authority

- SLA-CREDIT-10 and SLA-CREDIT-20 are auto-approvable by the dispatcher on duty (TIER-1) and may be granted by the dispatch assistant without human sign-off.
- SLA-CREDIT-50 requires TIER-2 (shift supervisor) approval.
- SLA-CREDIT-100 requires TIER-2 approval plus a logged root cause.
- A customer may not receive more than one credit per order. Where multiple failures occurred (late AND reassigned AND poorly notified), apply the single highest applicable tier.

## Dispatcher obligations when a breach fires

1. Diagnose why: is the driver STUCK, still queuing at the restaurant, or simply on a long route?
2. Attempt mitigation first — reassignment or PRIORITIZE per the Reassignment Policy. Credits compensate failure; they do not replace fixing the delivery.
3. Notify the customer proactively before they complain. A breach the customer hears about from us first, with an honest revised ETA, measurably reduces complaint volume.
4. Record the predicted vs. actual lateness so the weekly ops review can tune SLA multipliers.
