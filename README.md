# FleetMind

**A food-delivery fleet-ops platform for a simulated Kolkata, built the way you'd build it for real: seven services on a Kafka backbone, JWT + multi-tenancy at an API gateway, Redis caching with a hand-rolled leader lease, circuit breakers, a CQRS read side, distributed tracing — and an AI dispatcher that has to earn every single write through a transactional outbox.**

[![CI](https://github.com/SubbyDutta/FLEETMIND/actions/workflows/ci.yml/badge.svg)](https://github.com/SubbyDutta/FLEETMIND/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Kafka](https://img.shields.io/badge/Kafka-Avro%20%2B%20Schema%20Registry-231F20)
![Python](https://img.shields.io/badge/Python-ai--service-3776AB)
![Redis](https://img.shields.io/badge/Redis-cache%20%2B%20leader%20lease-DC382D)

Here's the demo in one paragraph. A rider freezes mid-delivery on a real Kolkata street. Within the detection window, Kafka Streams raises a `STUCK` alert. A dispatcher — logged in through the gateway with a tenant-scoped JWT — asks the AI agent what's wrong. The agent reads live telemetry, searches *its tenant's* operational runbooks, picks an idle rider nearby via a PostGIS KNN query, and executes a reassignment: order row and outbox row committed in **one transaction**, published to Kafka by whichever service replica currently holds the Redis leader lease, applied by the simulator, and reflected on the live map a tick later. The customer gets an HTML email about it. Nothing in that chain is mocked, and every hop of it shows up as one trace in Jaeger.

Every hard problem in here was hit on purpose — and several were hit by accident first, which is better. The double-booking bug that motivated the outbox was observed live before it was fixed. The N+1 query problem was measured (51 SQL statements) before it was killed (2). The cross-tenant data leak existed for exactly one commit, with a before/after curl to prove it.

## The system

```mermaid
flowchart LR
    WEB["web · React + Leaflet · :5173"]
    GW["api-gateway · :8090<br/>JWT validation · rate limiting"]

    subgraph CS["command-service · :8086 · gRPC :9091"]
        AUTH["auth: login, RS256 keys, JWKS"]
        PROJ["Kafka → Postgres projections"]
        TOOLS["ToolService — reassign / notify"]
        PUB["OutboxPublisher<br/>(leader-lease gated)"]
    end

    QS["query-service · :8083<br/>GraphQL, batched reads"]
    NS["notification-service · :8084<br/>email + in-app, idempotent"]

    subgraph KAFKA["Kafka · Avro + Schema Registry"]
        gps(["gps.pings"])
        ord(["orders"])
        eta(["eta.updates"])
        al(["alerts"])
        da(["dispatch.actions"])
        dlt(["*-dlt topics"])
    end

    SP["stream-processor<br/>Kafka Streams: SLA / stuck / idle"]

    subgraph SIM["fleet-simulator · :8085"]
        ME["27 riders · OSRM routes · 3s tick"]
        DAL["dispatch consumer<br/>retries 1s→2s→4s → DLT"]
    end

    subgraph AI["ai-service · gRPC :50051"]
        DAG["dispatch agent · 7 tools"]
        AAG["analytics agent · read-only"]
        RAG["per-tenant runbook RAG"]
    end

    PG[("Postgres<br/>PostGIS + pgvector")]
    RD[("Redis<br/>cache · lease · rate limits")]
    MH["MailHog :8025"]

    WEB --> GW
    GW --> CS
    GW --> QS
    CS --> RD
    GW --> RD
    SIM --> gps & ord
    gps & ord --> SP
    SP --> eta & al
    gps & ord & eta & al --> PROJ
    PROJ --> PG
    QS --> PG
    CS -->|"gRPC · x-tenant-id"| AI
    RAG --> PG
    DAG -->|"tool calls"| TOOLS
    TOOLS -->|"row + outbox, one tx"| PG
    PG --> PUB
    PUB --> da
    da --> DAL
    al & ord & da --> NS
    NS --> MH
```

Six Avro event types under Schema Registry contracts with enforced backward evolution. `orders`, `dispatch.actions`, and `eta.updates` are keyed by `orderId`, so per-order sequencing survives partitioning — a property the error-handling design leans on hard (see below).

## The engineering

### The outbox exists because of a bug I watched happen

Before the outbox relay, the agent's first real reassignment committed to Postgres while the simulator never learned about it: the old rider sat orphaned, the new one was double-booked, and GPS pings kept overwriting the claim. The fix is the textbook pattern, implemented properly: [`ReassignService`](command-service/src/main/java/com/ReassignService.java) writes the order update and the outbox row in one transaction, and [`OutboxPublisher`](command-service/src/main/java/com/OutboxPublisher.java) drains with `FOR UPDATE SKIP LOCKED`, publishing before marking — at-least-once, because between losing an action and duplicating one, you pick duplicating and make the consumer idempotent.

### The publisher runs on exactly one replica — via a hand-rolled Redis lease

Scale the command service to N replicas and you'd get N outbox publishers double-sending. The leader lease is `SET NX EX` with a Lua compare-and-swap renewal (check-the-value-then-extend has a TOCTOU race; the Lua script makes it atomic), a `host-pid-uuid` fencing value, and **no explicit release** — a dying leader just lets the TTL lapse, and failover lands inside one TTL window. Two deliberate decisions worth defending in a review: the lease **fails open** (Redis down → everyone publishes, because duplicate delivery is survivable and a stalled outbox is not), and Redis runs with `noeviction` (an `allkeys-lru` policy could evict the lease key under memory pressure — which means two leaders). Verified live with two instances: one leader, kill it, the other takes over; the original reboots and correctly stays a follower.

### The cache layer degrades toward the database, never away from it

Cache-aside over the hot read paths with per-cache TTLs (3s for driver/order state that changes every tick, 10s for slower aggregates). A custom error handler means a Redis outage silently becomes a Postgres read — a cache that can take down reads isn't a cache, it's a dependency. Every key is **prefixed with the tenant** (`fm:cache:` + tenant + key), because a shared cache is the single easiest place to leak data across tenants after you've carefully scoped every SQL query. Measured live: 93.5% hit rate on the driver-list path.

### Auth is a gateway, and tenancy is a relay race

All traffic enters through a Spring Cloud Gateway that validates **RS256** JWTs against a JWKS endpoint — asymmetric keys because three services validate tokens and only one should ever be able to mint them; that shrinks the blast radius of a compromised validator to zero. Redis-backed token buckets rate-limit per-IP on login (hammering it live: 5 × 200, then 429s) and per-subject on the API. Roles are `ADMIN` / `DISPATCHER` / `VIEWER`, enforced at the endpoint level — a viewer can watch the map but gets a 403 trying to reach analytics.

The interesting part is how the tenant travels. A claim in the JWT becomes a `ThreadLocal` in the Java filter, crosses the gRPC boundary as `x-tenant-id` metadata via server interceptors, lands in a Python `ContextVar`, and ends as a `WHERE tenant_id = ?` predicate on every human-facing query — including vector search, so each tenant's agents retrieve only their own runbooks. The context accessor is **fail-closed**: a code path that forgets to establish tenancy throws, it doesn't return everything. Machine paths (Kafka projections) deliberately stay tenant-defaulted — the dumb-writer/scoped-reader split keeps Avro contracts untouched.

Proof over promises: one tenant's runbooks contain a honeypot codeword. An eval scenario logs in as the *other* tenant and tries to get the agent to surface it — retrieval comes back empty and the agent refuses honestly. And the commit history keeps the receipt: the same curl that returned a foreign tenant's order before tenancy landed returns zero rows after.

### Reads got their own service, and the N+1 kill is measured, not claimed

Dashboard reads go through a separate GraphQL query service — CQRS-lite: REST for writes, client-shaped queries for reads, and the two sides can't contaminate each other. The naive resolver design was benchmarked first: **51 SQL statements** to render 50 orders with drivers and alerts. With `@BatchMapping` DataLoader batching: **2**. Depth and complexity guardrails reject a nested query bomb before a single SQL statement executes. Security context provably propagates onto the DataLoader threads — batch-mapped fields stay tenant-scoped.

### Failure policy is chosen per path, not defaulted

The agent's write path gets bounded retries (1s → 2s → 4s) and a dead-letter topic, with a byte-array producer template so even *undeserializable* poison lands on the DLT with forensic headers. Non-blocking `@RetryableTopic` was evaluated and **rejected**: it trades per-key ordering for throughput, and dispatch actions for one order must apply in sequence. Projections get the opposite policy — retry, then log-and-skip, no DLT — because they're rebuildable by replay. Same framework, opposite policies, both defensible.

Circuit breakers guard both external edges. The OSRM routing breaker required moving the existing try/catch *outside* the breaker — a breaker that never sees failures never opens. The AI-service breaker wraps stream *consumption*, not the call, because gRPC blocking stubs are lazy and failures surface on `hasNext()`. When it's open, the API returns an honest 503 with `circuit_open: true` instead of hanging a dispatcher's browser for two minutes. Watched live through the full state machine: CLOSED → OPEN → HALF_OPEN → probe → CLOSED.

### The AI agents are treated like untrusted code

Nothing here trusts a prompt. The analytics agent *cannot* call `reassign_order`: the tool isn't declared to the model **and** isn't resolvable at execution time, and a unit test fakes the model requesting it and asserts "unknown tool". Underneath that, every analytics query opens with `SET TRANSACTION READ ONLY` — transaction-scoped, not session-scoped, because the connection pool is shared with a component that writes. Both write tools require an explicit `confirm=true` schema argument, and the dispatch prompt requires a runbook citation before any action — with a grader that fails any transcript where an action precedes a runbook search.

The agents are evaluated like software: a 30-scenario harness runs the real model through the production loop against canned tool worlds (canned tools clone each real tool's name, description, and schema, so the model sees production-identical declarations). Scenarios cover happy paths, one-scenario-per-prompt-rule regressions, refusals, prompt injection inside tool results, and cross-tenant isolation. Every episode is graded twice — mechanically on *actions*, by an LLM judge on *words* — and the harness is mutation-tested: a zero-cost dry run must catch four planted violations before any API quota is spent. The gate is deliberately 85%, not 100%, because LLMs aren't bit-deterministic and a 100% bar teaches people to ignore red builds.

### Notifications are an exercise in idempotency

The notification service consumes alerts, order terminals, and dispatch actions, and turns them into Thymeleaf HTML emails (MailHog in dev) plus in-app rows — deduped by `topic-partition-offset` in its own table, so an at-least-once redelivery never emails a customer twice. It boots with `auto-offset-reset: latest` on purpose: `earliest` on a first deploy would replay the entire event history and blast every email at once. Per-user, per-event-type channel preferences; a mail failure propagates so the retry ladder and DLT get their shot.

### And you can watch all of it

Micrometer metrics to Prometheus, provisioned Grafana dashboards (consumer lag, HTTP percentiles, cache hit rate per cache, which instance holds the outbox lease, breaker states), and OpenTelemetry traces to Jaeger — the dispatch round-trip is a single 4-span trace across two services and two Kafka hops. The known tracing gaps are documented as design notes with articulated fixes, not hidden: the outbox breaks the trace (a scheduled poller has no request context; the fix is persisting `traceparent` on the outbox row — consciously not built), and the Kafka Streams hop is untraced.

## Numbers that were actually measured

- **51 → 2** SQL statements for 50 orders, naive vs. batched GraphQL resolvers (logs kept)
- **93.5%** Redis cache hit rate on the hot driver-list path
- Outbox leader failover ≤ one lease TTL; rebooted ex-leader correctly stays follower
- Full breaker lifecycle observed live on both edges: OPEN at 50% failure rate, timed HALF_OPEN transitions, ~14ms fallbacks vs ~2s timeout failures
- Login rate limit: 5 × 200 then 429s under a live hammer
- **4-span** distributed trace across 2 services + 2 Kafka hops
- Eval smoke: 14.9s, 9-step episode with 2 real RAG retrievals, both graders green; dry run catches all 4 planted violations at $0

*(One number this README refuses to fake: the full 30-scenario eval scorecard is still being run in batches on free-tier quota. The harness, dry run, and smoke scenarios are green.)*

## Stack

| Tech | Why it's here |
|---|---|
| Java 21 · Spring Boot | The transactional, Kafka-consumer side of the house |
| Apache Kafka + Avro + Schema Registry | Replayable event log with typed, evolvable contracts; co-partitioned keys make ordering and stream joins work |
| Kafka Streams | Stateful windowed detection (SLA / stuck / idle) and live ETA joins |
| Spring Cloud Gateway + JWT (RS256) | Single entry point: auth, rate limiting, tenant routing |
| Redis | Read cache, outbox leader lease, rate-limit buckets — three jobs, one box, each with a deliberate failure mode |
| gRPC + Protobuf | Typed polyglot boundary between Java and Python — a real contract, not glued JSON |
| Python + Gemini | The agent side; function calling at temperature 0 |
| Postgres + PostGIS + pgvector | Projections, geo KNN (nearest idle rider), and embeddings in one store — no second database until it earns its keep |
| GraphQL (query-service) | Client-shaped dashboard reads with DataLoader batching |
| OSRM (self-hosted) | Real Kolkata road geometry for rider movement |
| React + Leaflet | Live moving-marker ops console, no map-API key |
| Prometheus · Grafana · Jaeger | Metrics tell you *that*, traces tell you *where* |

## Run it

Prereqs: Docker, JDK 21, Python 3.12+, Node 18+, a [Gemini API key](https://aistudio.google.com/apikey).

**1. One-time: build the OSRM routing graph** (`data/` is git-ignored — bring your own extract):

```powershell
# place a Kolkata-area OSM extract (e.g. Geofabrik's West Bengal) at data/kolkata.osm.pbf
docker run --rm -v ${PWD}/data:/data osrm/osrm-backend osrm-extract -p /opt/car.lua /data/kolkata.osm.pbf
docker run --rm -v ${PWD}/data:/data osrm/osrm-backend osrm-partition /data/kolkata.osrm
docker run --rm -v ${PWD}/data:/data osrm/osrm-backend osrm-customize /data/kolkata.osrm
```

**2. Infrastructure** — Kafka, Schema Registry, Postgres, Redis, OSRM, MailHog, Kafka-UI, Prometheus, Grafana, Jaeger:

```powershell
docker compose up -d
```

**3. AI service** — create `ai-service/.env` (git-ignored, never commit it):

```ini
GEMINI_API_KEY=<your key>
DATABASE_URL=postgresql://postgres:fleetmind@localhost:5432/fleetmind
AGENT_MODEL=gemini-2.5-flash
```

```powershell
cd ai-service
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m app.rag.index        # embed + index the runbooks into pgvector, per tenant
```

**4. Services — boot order matters.** On fresh Kafka, the stream processor derives its internal topic layout from source topics, so their producers boot first:

```powershell
./gradlew :command-service:bootRun        # 1 — auth, projections, tools, outbox
./gradlew :fleet-simulator:bootRun        # 2 — the world: riders, orders, GPS
./gradlew :stream-processor:bootRun       # 3 — after source topics exist
./gradlew :query-service:bootRun          # 4 — GraphQL reads
./gradlew :notification-service:bootRun   # 5 — email + in-app
./gradlew :api-gateway:bootRun            # 6 — the front door
cd ai-service; uvicorn app.main:app --port 8000    # 7 — agents (gRPC :50051)
```

**5. UI:**

```powershell
cd web; npm install; npm run dev        # http://localhost:5173
```

Log in (`dispatcher@acme` / `demo123`), click a rider, **freeze** it, watch the stuck alert fire, then ask the agent *"what's wrong with order X and what should I do?"*. Check MailHog at :8025 for the customer email.

### Port map

| Port | What |
|---|---|
| 5173 | web UI (Vite dev) |
| 8090 | **api-gateway — the only port a client should talk to** |
| 8086 / 9091 | command-service REST+SSE / gRPC ToolService |
| 8083 | query-service (GraphQL + GraphiQL) |
| 8084 | notification-service |
| 8085 | fleet-simulator (incident endpoints) |
| 8080 | stream-processor (interactive queries) |
| 50051 / 8000 | ai-service gRPC / HTTP |
| 9092 / 8081 / 8088 | Kafka / Schema Registry / Kafka-UI |
| 5432 / 6379 / 5000 | Postgres / Redis / OSRM |
| 1025 / 8025 | MailHog SMTP / inbox UI |
| 9099 / 3000 / 16686 | Prometheus / Grafana / Jaeger |

## Repo tour

```
common-events/         6 Avro schemas — the event contracts (codegen, no hand-written Java)
common-proto/          4 gRPC contracts: agent chat, tools, telemetry stream, status
api-gateway/           front door: JWT validation, Redis rate limiting, routing
fleet-simulator/       the world: 27 riders, 18 real Kolkata restaurants, OSRM movement,
                       dispatch consumer with retry ladder + DLT
stream-processor/      Kafka Streams: live ETA join + SLA/stuck/idle windowed detectors
command-service/       auth + RS256 keys + JWKS, projections → Postgres, REST + SSE,
                       gRPC ToolService, transactional outbox + leased publisher
query-service/         GraphQL read side: batched resolvers, depth/complexity guardrails
notification-service/  idempotent Kafka consumers → Thymeleaf email + in-app rows
ai-service/            Python: dispatch + analytics agents, per-tenant runbook RAG
                       (hybrid vector+FTS, RRF), eval harness under evals/
web/                   React + Leaflet ops console: login, live map, alerts, agent drawer
db/                    Postgres image (PostGIS + pgvector) + schema
observability/         Prometheus scrape config + provisioned Grafana dashboards
.github/               CI: Gradle build + tests against the project's own db image
```

## Testing

```powershell
./gradlew test          # JUnit across all six Java modules (Postgres container must be up)
cd ai-service; pytest   # agent loop, tool isolation, tenancy, retrieval, analytics SQL
```

Highlights of what's pinned by tests rather than by hope:

- The core outbox invariant: order update and outbox row commit or roll back **together**; busy / unknown / cross-tenant / delivered targets are rejected with nothing written.
- DLT routing: deserialization poison skips retries and dead-letters via the byte-array template; transient failures walk the full 1s → 2s → 4s ladder first. (These tests caught two stale-docs assumptions, including the framework's actual `-dlt` suffix.)
- JWT: forged foreign-key tokens and expired tokens are rejected; missing tenant context **fails closed**.
- Java `Timestamp` survives the Redis JSON serializer round-trip — verified, not assumed.
- Tool isolation: a faked model request for a write tool from the read-only agent yields "unknown tool".
- Notification dedupe: a redelivered offset is skipped; a mail failure propagates so the DLT machinery engages.

The eval harness is the integration suite for agent *behavior*:

```powershell
cd ai-service
python -m evals.dryrun                       # $0 — lint scenarios, verify graders catch planted violations
python -m evals.runner --resume --pace=45    # run all 30 against the real model (quota-friendly)
python -m evals.report                       # grade, print scorecard, exit 1 below 85%
```

## What's left

One phase: **load testing**. Batch the GPS-ping projection writes (Kafka batch listener + `batchUpdate`, collapse-to-latest-per-driver), then k6 against multiple command-service replicas — kill one mid-test and prove the consumer group rebalances without loss. The before/after write-amplification numbers land here when it's done.

---

Built by **Subham Dutta** — [subhamdutta4289@gmail.com](mailto:subhamdutta4289@gmail.com) · [github.com/SubbyDutta](https://github.com/SubbyDutta)
