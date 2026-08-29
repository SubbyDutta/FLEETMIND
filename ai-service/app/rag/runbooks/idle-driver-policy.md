# Idle Driver Policy (RB-IDLE)

## What the IDLE_DRIVER alert means

The platform flags a driver as idle when, over a full **5-minute tumbling window**, the driver was **not assigned to any order**, sent at least **10 GPS pings** (about 30 seconds of data at the 3-second ping rate), and moved **less than 20 meters** in total. The window has a 1-minute grace period and only reports after it closes, so the alert lands roughly 6 minutes after the stillness began. Severity is always **LOW** — this is housekeeping, never an emergency.

Idle time is expected and healthy in moderation. With 12 riders and a new order entering the system roughly every 90 seconds, drivers naturally cycle through short idle gaps between deliveries. The alert exists to catch *prolonged* stillness, positioning problems, and end-of-shift drift — not to punish a rider catching their breath after a drop-off.

## Distinguishing idle from stuck

These are different alerts with different meanings and must not be confused:

- **IDLE_DRIVER**: driver has **no order**, stationary 5+ minutes, severity LOW. A utilization concern.
- **STUCK**: driver **has an active order**, stationary 8+ minutes, severity HIGH. A delivery-at-risk emergency covered by the Stuck Driver Protocol.

An idle driver is inventory waiting to be used; a stuck driver is an order failing in real time.

## Response ladder

- **Single idle alert (≈6 min stationary)**: no action. Normal between-order rest.
- **Two consecutive idle alerts (≈11+ min stationary)**: check the driver's position. If they are parked far from the restaurant cluster, suggest repositioning (see staging guidance below). If demand is currently low across the fleet, leave them be.
- **Idle 20+ minutes during peak demand while orders queue**: contact the driver. If unresponsive, mark them off-shift so the assignment engine stops considering them, and note it for the shift report.
- **Idle immediately after being freed from a reassignment or recovery**: expected — a recovered driver rejoins the pool as idle and typically receives a new order within one or two order cycles (about 90 seconds each).

## Staging guidance — where idle drivers should wait

Kolkata demand centers on the centre-city restaurant cluster: Park Street and its immediate surroundings (Peter Cat, Mocambo, Flurys, Trincas are effectively one block), the Arsalan/Ballygunge corridor to the south-east, and the Aminia/Nizam's pocket to the north near Esplanade. Drop-offs scatter across the wider central-south zone.

Repositioning suggestions for long-idle drivers, in order of preference:

1. Move toward Park Street if fewer than 3 riders are currently within 1 km of it — it is the highest-frequency pickup zone.
2. Otherwise hold near the Arsalan/Ballygunge corridor as the secondary staging point.
3. Never suggest repositioning more than 2 km — the fuel and time cost outweighs the assignment-probability gain at this fleet size.

## Utilization reporting

Shift reports should track, per driver: total idle minutes, idle-alert count, and longest continuous idle stretch. Consistent outliers (a driver idling 3× the fleet median across multiple shifts) go to TIER-2 as a scheduling or conduct conversation — the alert stream is the evidence, not the verdict.
