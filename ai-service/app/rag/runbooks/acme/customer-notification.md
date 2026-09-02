# Customer Notification Playbook (RB-NOTIFY)

## Principles

1. **We tell the customer before they ask.** A proactive message about a 10-minute delay is a minor annoyance; a silent 10-minute delay is a lost customer. Every SLA_BREACH prediction and every reassignment generates a customer-facing message.
2. **Honest ETAs only.** Quote the platform's live ETA (recomputed every few seconds from the driver's actual position) plus a 3-minute buffer. Never quote an optimistic number to soften the message — a second broken promise is worse than one honest delay.
3. **One message per event, rate-limited.** Never send more than one notification per order per 10 minutes unless the situation has materially changed (e.g. delay message followed by a reassignment message is fine; two delay messages 4 minutes apart is not).

Notifications are dispatched via the NOTIFY dispatch action with the order id; the notification channel handles delivery. Like all write actions, NOTIFY flows through the outbox — never sent ad hoc.

## When to notify — trigger table

| Situation | Notify? | Template |
|---|---|---|
| Order assigned, everything nominal | No | — |
| Predicted delay under 5 minutes past deadline | No — usually self-corrects | — |
| SLA_BREACH with predicted lateness 5+ minutes | Yes, immediately | DELAY |
| Driver flagged STUCK and order deadline within 15 min | Yes | DELAY |
| Order reassigned to a new driver | Yes, always | REASSIGNED |
| Order cancelled | Yes, always, with credit statement | CANCELLED |
| Delivered late (actual breach) | Yes, with the applicable SLA credit | LATE-APOLOGY |

## Templates

**DELAY** — "Hi {customerName}, your order from {restaurantName} is running about {delayMinutes} minutes behind. Your rider {driverName} is on the way and we now expect delivery by {revisedEta}. Sorry for the wait — we're on it."

**REASSIGNED** — "Hi {customerName}, we've switched your delivery to a new rider, {newDriverName}, to get your order from {restaurantName} to you faster. Updated delivery estimate: {revisedEta}."

**CANCELLED** — "Hi {customerName}, we're sorry — we had to cancel your order from {restaurantName} because {plainLanguageReason}. A {creditPercent}% credit has been applied per our delivery promise. We'd love another chance soon."

**LATE-APOLOGY** — "Hi {customerName}, your order arrived {lateMinutes} minutes later than promised. We've applied a {creditPercent}% credit ({creditCode}) to your account automatically. Thank you for your patience."

Template rules: always use the customer's first name; always name the restaurant; state times as clock times, not durations, where possible ("by 8:45 PM" beats "in 25 minutes"); never blame the driver, the restaurant, or traffic in the first sentence — lead with the fix.

## What never goes in a customer message

- Internal jargon: alert names (STUCK, SLA_BREACH), driver ids (say "your rider John", never "driver-1"), window mechanics, severity levels.
- Speculation about cause before it is confirmed.
- A revised ETA that is not backed by the live ETA feed.
- Any promise of credit outside the tiers defined in the SLA and Credit Policy.
