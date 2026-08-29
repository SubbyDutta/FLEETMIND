# Order Cancellation Policy (RB-CANCEL)

## Cancellation is the last resort

FleetMind's dispatch philosophy: an order is only cancelled when every recovery path has failed or the customer asks. Reassignment, prioritization, and honest delay communication come first, in that order. Cancellation destroys prepared food, costs the restaurant, and usually costs the customer relationship — a 40-minute-late delivery with a 100% credit retains more customers than a cancellation.

Cancellation is executed via the **FLAG_CANCEL** dispatch action on the order id. Like every write, it flows through the transactional outbox. FLAG_CANCEL requires **TIER-2 (shift supervisor) approval** in all cases except an explicit customer request, which the dispatcher may honor directly.

## Valid grounds for FLAG_CANCEL

1. **Customer requests cancellation.** Honor immediately at any stage. Refund rules: full refund if the restaurant has not started preparation; if food is prepared or picked up, apply SLA-CREDIT-100 handling (100% credit) only when the delivery was already predicted late — otherwise standard change-of-mind terms apply.
2. **Undeliverable order.** Two reassignments already consumed (the maximum under the Reassignment Policy), no idle driver available, and predicted lateness exceeding 40 minutes.
3. **Restaurant failure.** The restaurant cannot fulfil — closed unexpectedly, out of stock, or refuses to release the order. Also triggers a restaurant-partner note to TIER-2.
4. **Wrong or unreachable drop-off.** The drop-off address is invalid, outside the service zone, or the customer is unreachable after delivery arrival plus 10 minutes of contact attempts.
5. **Safety.** Any situation where completing the delivery would put the rider at risk (flooding, road closure into an unsafe area, threatening customer behavior). Rider safety overrides every delivery metric; this also opens a TIER-3 incident if there was actual danger.

## Invalid grounds — never cancel for these

- A single STUCK or SLA_BREACH alert. These are recoverable states with dedicated protocols.
- Dispatcher convenience or queue pressure.
- A driver's request to be released from an order — that is a reassignment, not a cancellation.
- Predicted lateness alone, however large, while a viable reassignment path still exists.

## Execution checklist

1. Confirm grounds and obtain TIER-2 approval (unless customer-initiated).
2. Issue FLAG_CANCEL through the dispatch pipeline.
3. Send the CANCELLED customer notification immediately, including the plain-language reason and the credit applied — see the Customer Notification Playbook templates.
4. If a driver is en route or holding the food, notify them to stand down; the driver returns to the idle pool.
5. Log the root cause category (customer request / undeliverable / restaurant failure / bad address / safety) — the weekly ops review tracks cancellation rate per category, and anything above 2% of orders overall is treated as a systemic failure to investigate.

## Interaction with credits

A cancelled order never receives both a refund and an SLA credit — the 100% refund IS the terminal credit (code SLA-CREDIT-100 where lateness caused it). Partial credits (10/20/50) apply only to delivered orders per the SLA and Customer Credit Policy.
