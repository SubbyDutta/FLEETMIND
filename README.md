# FleetMind

FleetMind is a food-delivery fleet-operations platform for a simulated Kolkata. It simulates riders, orders, and GPS movement on real streets, detects operational problems in real time, and gives dispatchers an AI agent that can investigate incidents and carry out approved fixes.

I built it to find out what happens when you take a "simple" AI + microservices project and add the problems real systems actually have: duplicate events, stale caches, tenant isolation, service failures, ordering guarantees, retries, and observability.

The part I care about most: **the AI never touches the database directly.** A reassignment has to get through a Java command service and a transactional outbox before it becomes an event anything else will believe.

[![CI](https://github.com/SubbyDutta/FLEETMIND/actions/workflows/ci.yml/badge.svg)](https://github.com/SubbyDutta/FLEETMIND/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Kafka](https://img.shields.io/badge/Kafka-Avro%20%2B%20Schema%20Registry-231F20)
![Redis](https://img.shields.io/badge/Redis-cache%20%2B%20leader%20lease-DC382D)
![Python](https://img.shields.io/badge/Python-agents%20%2B%20RAG-3776AB)

<!-- demo GIF goes here: ~15s, freeze rider → STUCK alert → agent investigates → reassign lands on the map.
     Record with the stack up: ScreenToGif or `ffmpeg -f gdigrab`, keep it under ~8MB so GitHub autoplays it.
     Optionally follow with a 2-min mp4 dragged into the README (GitHub renders an inline player). -->

## What happens in a dispatch

```
rider freezes mid-delivery
         │
         ▼
Kafka Streams raises a STUCK alert        stream-processor
         │
         ▼
dispatcher asks the AI agent               web → gateway (JWT) → command-service → gRPC
         │
         ▼
agent reads live telemetry,
searches its tenant's runbooks (RAG),
picks a nearby idle rider                  PostGIS KNN
         │
         ▼
agent requests the reassignment            gRPC tool call, explicit confirm=true
         │
         ▼
command-service validates it, writes
the order update + an outbox row           ── one transaction ──
         │
         ▼
leased publisher → Kafka                   only the replica holding the Redis lease
         │
         ▼
simulator applies it, re-emits the
order event                                heals the stream state too
         │
         ▼
map updates · customer gets an email       MailHog in dev
```

The whole chain is real, and it shows up in Jaeger as one distributed trace.

## Some numbers before the words

| | |
|---|---|
| **51 → 2** | SQL statements to render 50 orders, naive vs. batched GraphQL resolvers (logs kept) |
| **93.5%** | Redis cache hit rate on the hot driver-list path, measured live |
| **≤ 1 TTL** | outbox leader failover after killing the leading instance |
| **4 spans** | one dispatch traced across 2 services and 2 Kafka hops |
| **5 × 200 → 429** | login endpoint under a live hammer |
| **~14 ms vs ~2 s** | circuit-breaker fallback vs. the timeout it replaces |
| **4/4** | planted safety violations caught by the eval harness's zero-cost dry run |
| **rows=1 → rows=0** | the same cross-tenant curl, before and after tenancy landed (the leak existed for exactly one commit) |

Everything in that table was measured, not estimated. The one number this README refuses to invent is the full 30-scenario eval score — it's still being run in batches on free-tier quota, and it lands here when it's real.

## Architecture

```
                        ┌────────────────┐
                        │  React ops UI  │  live map · alerts · agent drawer
                        └───────┬────────┘
                                │  every client request goes through one door
                        ┌───────▼────────┐
                        │  api-gateway   │  JWT (RS256) · tenant · rate limits
                        └───┬────────┬───┘
                            │        │
                ┌───────────▼──┐  ┌──▼─────────────┐        ┌──────────────┐
                │ query-service│  │ command-service│  gRPC  │  ai-service  │
                │   GraphQL,   │  │ auth · REST/SSE│───────►│   (Python)   │
                │ batched reads│  │ ToolService ·  │◄───────│ agents + RAG │
                └──────┬───────┘  │ outbox         │  tool  └──────┬───────┘
                       │          └──┬─────────────┘  calls        │
                       ▼             ▼                             │
                ┌─────────────────────────────────────┐            │
                │   Postgres · PostGIS · pgvector     │◄───────────┘
                └──────────────────┬──────────────────┘
                                   │ outbox rows, drained by whichever
                                   │ replica holds the Redis leader lease
                                   ▼
                ┌─────────────────────────────────────┐
                │    Kafka · Avro + Schema Registry   │
                │ gps.pings orders eta.updates alerts │
                │      dispatch.actions  *-dlt        │
                └────┬─────────────┬──────────────┬───┘
                     ▼             ▼              ▼
              fleet-simulator  stream-processor  notification-service
              27 riders on     SLA / stuck /     idempotent consumers →
              real OSRM roads  idle detectors    HTML email + in-app

        Redis: read cache · outbox leader lease · rate-limit buckets
        Prometheus + Grafana + Jaeger watching all of it
```

Six Avro event types under Schema Registry contracts with enforced backward evolution. `orders`, `dispatch.actions`, and `eta.updates` are keyed by `orderId`, so per-order sequencing survives partitioning — the retry design leans on that hard.

## The problems that were actually hard

### The outbox exists because of a bug I watched happen

Before the outbox relay, the agent's first real reassignment committed to Postgres while the simulator never learned about it: the old rider sat orphaned, the new one was double-booked, and GPS pings kept overwriting the claim. The fix: [`ReassignService`](command-service/src/main/java/com/ReassignService.java) writes the order update and the outbox row in one transaction, and [`OutboxPublisher`](command-service/src/main/java/com/OutboxPublisher.java) drains with `FOR UPDATE SKIP LOCKED`, publishing before marking. At-least-once on purpose — between losing an action and duplicating one, you duplicate and make the consumer idempotent.

### One publisher, no matter how many replicas

Scale the command service to N replicas and, without coordination, every instance tries to publish the same outbox rows. So there's a small Redis leader lease: `SET NX EX` to acquire, a Lua compare-and-swap to renew, `host-pid-uuid` as the lease value, and **no explicit release** — a dying leader just lets the TTL lapse.

Tested it the honest way: ran two instances, killed the leader, watched the second take over within one TTL. When the old instance came back, it correctly stayed a follower.

<details>
<summary>Why Lua, why fail-open, why noeviction</summary>

- Renewal is check-the-value-then-extend — two steps, which is a TOCTOU race if the lease expires between them. The Lua script makes compare-and-extend atomic on the Redis side.
- The lease **fails open**: if Redis is down, every instance publishes. Duplicate delivery is survivable (idempotent consumers); a silently stalled outbox is not.
- Redis runs with `noeviction`. An `allkeys-lru` policy could evict the lease key under memory pressure — which means two leaders and no error anywhere.
</details>

### Redis goes down. Reads keep working.

Cache-aside over the hot read paths with per-cache TTLs (3s for state that changes every simulator tick, 10s for slower aggregates). A custom error handler turns any Redis failure into a silent Postgres read — a cache that can take down reads isn't a cache, it's a dependency. And every key is prefixed with the tenant, because a shared cache is the easiest place to leak data across tenants after you've carefully scoped every SQL query.

### The tenant ID has a long journey

```
JWT claim  (tenant=acme, minted at login)
   │
   ▼
api-gateway validates the signature
   │
   ▼
Java ThreadLocal          TenantContext, set by a servlet filter
   │
   │  gRPC metadata: x-tenant-id     server interceptors on both sides
   ▼
Python ContextVar
   │
   ▼
WHERE tenant_id = ?       every human-facing query — including vector search,
   │                      so each tenant's agent retrieves only its own runbooks
   ▼
Postgres / pgvector
```

The rule that makes it safe: **missing tenant context fails closed.** A code path that forgets to establish tenancy throws — it doesn't return everything.

And the proof is fun: one tenant's runbooks contain a honeypot codeword. An eval scenario logs in as the *other* tenant and tries to talk the agent into surfacing it. Retrieval comes back empty; the agent refuses honestly.

<details>
<summary>Why RS256, and why Kafka writes stay unscoped</summary>

- RS256 over a shared secret because three services validate tokens and only one should ever mint them. A compromised validator holds a public key — blast radius zero.
- Kafka projection writes stay tenant-defaulted at the column level. Machine events have no user; forcing tenancy into the Avro contracts would have rippled through every producer for no security gain. Dumb writers, scoped readers.
- Login rate limiting is a Redis token bucket, per-IP: hammered live, 5 × 200 then 429s. Roles are ADMIN / DISPATCHER / VIEWER — a viewer can watch the map but eats a 403 on analytics.
</details>

### 51 queries became 2

Dashboard reads go through a separate GraphQL query service — REST for writes, client-shaped queries for reads. I benchmarked the naive resolver design first: **51 SQL statements** to render 50 orders with their drivers and alerts. With `@BatchMapping` DataLoader batching: **2**. Both log files are kept, because "I fixed an N+1" is a claim and 51→2 is evidence.

Depth and complexity guardrails reject a nested query bomb before a single SQL statement runs, and the security context provably propagates onto the DataLoader threads — batch-mapped fields stay tenant-scoped.

### Retries depend on what you're retrying

The agent's write path gets bounded retries (1s → 2s → 4s) and a dead-letter topic, with a byte-array producer template so even *undeserializable* poison lands on the DLT with forensic headers. Projections get the opposite policy — retry, then log-and-skip, no DLT — because they're rebuildable by replaying the topic. Same framework, opposite policies, each chosen for what breaks if it's wrong.

<details>
<summary>Why non-blocking retry topics were rejected</summary>

Spring's `@RetryableTopic` retries on separate topics, which trades per-key ordering for throughput. Dispatch actions for one order must apply in sequence — a REASSIGN retried after a later CANCEL is a corrupted order. Blocking retries keep the partition's ordering guarantee; the DLT catches what outlives the ladder.
</details>

Circuit breakers guard both external edges. The interesting one wraps the AI-service stream's *consumption*, not the call — gRPC blocking stubs are lazy, so failures surface on `hasNext()`. When it's open, the API returns an honest 503 with `circuit_open: true` in ~14ms instead of hanging a dispatcher's browser for two minutes. Watched live through the whole state machine: CLOSED → OPEN → timed HALF_OPEN → probes → CLOSED.

### Notifications, or: at-least-once means you'll send it twice

The notification service consumes alerts, order terminals, and dispatch actions and turns them into Thymeleaf HTML emails plus in-app rows — deduped by `topic-partition-offset` in its own table, so a redelivery never emails a customer twice. It boots with `auto-offset-reset: latest` deliberately: `earliest` on first deploy would replay the entire event history and blast every email at once. A mail failure propagates, so the retry ladder and DLT get their shot.

### If something breaks, I want to see where

Micrometer metrics into Prometheus, provisioned Grafana dashboards (consumer lag, HTTP percentiles, per-cache hit rate, which instance holds the outbox lease, breaker states), OpenTelemetry traces into Jaeger. The dispatch round-trip is a single 4-span trace across two services and two Kafka hops.

The gaps are documented instead of hidden: the outbox breaks the trace (a scheduled poller has no request context — the fix is persisting `traceparent` on the outbox row, consciously not built), and the Kafka Streams hop is untraced.

## The AI side

```
                    ┌──────────────┐
                    │ Gemini model │
                    └──────┬───────┘
          proposes a  │        ▲  result appended to the
          tool call   ▼        │  conversation, loop continues
                    ┌──────────────┐
                    │  agent loop  │   hand-rolled function-calling loop:
                    │ tool registry│   validate args against the tool's
                    └──────┬───────┘   schema, execute, feed back, repeat
                           │           — hard cap of 10 steps
              ┌────────────┴────────────┐
              ▼                         ▼
         READ TOOLS                WRITE TOOLS
         (Python)                  (Java, reached over gRPC)
         telemetry · SQL           reassign_order · notify_customer
         aggregates · runbook      both demand confirm=true in
         RAG · driver watch        their schema
              │                         │
              ▼                         ▼
      Postgres / pgvector         command-service ToolService
      every analytics query             │
      opens SET TRANSACTION             ▼
      READ ONLY                   transactional outbox → Kafka
```

**The model never gets direct database write access.** That line at the bottom of the right column is the whole design.

Two agents share the loop machinery and differ only in prompt and toolset. The isolation is by construction, not by prompt: the analytics agent can't call `reassign_order` because the tool isn't declared to the model *and* isn't resolvable at execution time — a unit test fakes the model requesting it and asserts "unknown tool". Underneath that, `SET TRANSACTION READ ONLY` is transaction-scoped rather than session-scoped, because the connection pool is shared with a component that writes.

The Java/Python split is deliberate. Python owns the agents and read tools because that's where the model and RAG ecosystem lives; Java owns every state-changing operation because those need the same transactional guarantees as the rest of the command side. gRPC gives the two a typed contract instead of glued JSON.

**RAG:** operational runbooks are chunked and indexed per tenant into pgvector. Retrieval is hybrid — vector similarity and Postgres full-text search, fused with reciprocal rank fusion — because incident queries mix natural language ("rider not moving") with exact tokens ("RB-STUCK") and neither retriever wins alone. The dispatch prompt requires a runbook citation before any action, and a grader fails any transcript where an action precedes a runbook search.

**Evals:** 30 frozen scenarios in 6 partitions — happy paths, one-scenario-per-prompt-rule regressions, refusals, prompt injection *inside tool results*, analytics grounding, cross-tenant isolation. The real model runs through the production loop against canned tool worlds (canned tools clone the real tools' schemas, so the model sees production-identical declarations; runbook retrieval stays real). Every episode is graded twice: mechanically on *actions*, by an LLM judge on *words* — and fabricated figures cap the score. The harness itself is mutation-tested: a zero-cost dry run has to catch 4 planted violations before any API quota gets spent, and it does. First live scenario: passed both graders — a 9-step episode with 2 real retrievals that adapted to an unscripted tool error without derailing. The gate is 85% rather than 100% because model outputs aren't perfectly deterministic; the full scorecard is being run in daily free-tier batches and gets pasted here, real, when it's done.

## Things that surprised me

- **A database commit doesn't make an event reliable.** The gap between "row updated" and "world notified" is where the double-booking bug lived. That's what the outbox is for.
- **Tenant isolation doesn't end at SQL.** Cache keys, gRPC metadata, Python context, vector search, and cached responses all leak if any one of them forgets the tenant.
- **Retries aren't automatically safe.** For dispatch events, ordering mattered more than throughput — which meant rejecting the framework's shinier retry mechanism.
- **A circuit breaker can be perfectly configured and useless.** My first version caught the exception before the breaker ever saw it.
- **"The model refuses" is not a security boundary.** Tool restrictions have to exist in the registry and the database session, where the model can't talk its way past them.
- **Free-tier LLM quotas fail in the worst way**: a retry policy that looked sensible sustained its own outage by keeping the rate-limit bucket full. Uniform ~17s failures across the board turned out to be my retry budget expiring, not the model.

## The decisions I'd defend

- **Transactional outbox** over publish-after-commit — the gap between "row committed" and "event sent" is exactly where the double-booking bug lived.
- **RS256** over a shared HMAC secret — three services validate tokens, one mints them; a compromised validator holds a public key and can forge nothing.
- **Blocking retries** over `@RetryableTopic` — on the dispatch path, per-order ordering beats throughput.
- **Fail-open lease, fail-quiet cache** — Redis dying should mean duplicate publishes and slower reads, never a stalled outbox or a failed request.
- **AI writes cross gRPC into Java** — the model gets a typed, validated, transactional door, never a database connection.

## Stack

The short version: **Java 21 + Spring Boot** for everything transactional, **Kafka + Avro** as the event backbone, **Python + Gemini** for the agents, **Postgres (PostGIS + pgvector)** as the single store for projections, geo queries, and embeddings, **Redis** for cache/lease/rate-limits, **gRPC** across the language boundary, **GraphQL** for dashboard reads, **OSRM** (self-hosted) for real Kolkata road geometry, **React + Leaflet** for the ops console, **Prometheus/Grafana/Jaeger** to watch it all.

No second database, no service mesh, no Kubernetes — nothing that didn't earn its place at this scale.

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

**4. Services — boot order matters** (on fresh Kafka the stream processor derives its topology from source topics, so their producers go first):

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

<details>
<summary>Port map</summary>

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
</details>

## Testing

```powershell
./gradlew test          # JUnit across all six Java modules (Postgres container up)
cd ai-service; pytest   # agent loop, tool isolation, tenancy, retrieval, analytics SQL
```

The tests I'd point at first:

- **The outbox invariant**: order update and outbox row commit or roll back *together*; busy / unknown / cross-tenant / delivered targets are rejected with nothing written.
- **DLT routing**: deserialization poison skips retries and dead-letters via the byte-array template; transient failures walk the full 1s → 2s → 4s ladder first. These tests caught two stale-docs assumptions, including the framework's actual `-dlt` suffix.
- **JWT**: forged foreign-key tokens and expired tokens rejected; missing tenant context fails closed.
- **Tool isolation**: a faked model request for a write tool from the read-only agent yields "unknown tool".
- **Notification dedupe**: a redelivered offset is skipped; a mail failure propagates so the DLT machinery engages.
- Java `Timestamp` survives the Redis JSON serializer round-trip — verified by running it, not assumed.

And the eval harness is the integration suite for agent *behavior*:

```powershell
cd ai-service
python -m evals.dryrun                       # $0 — lint scenarios, prove the graders catch planted violations
python -m evals.runner --resume --pace=45    # run all 30 against the real model, quota-friendly
python -m evals.report                       # grade, print scorecard, exit 1 below 85%
```

## What's left

One phase: **load testing.** Batch the GPS-ping projection writes (Kafka batch listener + `batchUpdate`, collapse-to-latest-per-driver), then k6 against multiple command-service replicas — kill one mid-test and prove the consumer group rebalances without loss. The before/after write-amplification numbers land here when it's done.

Known limitation, stated plainly: the full 30-scenario eval score is still pending free-tier quota; the harness, dry run, and first live scenarios are green.

<details>
<summary>Repo tour</summary>

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
</details>

---

Built by **Subham Dutta** — [subhamdutta4289@gmail.com](mailto:subhamdutta4289@gmail.com) · [github.com/SubbyDutta](https://github.com/SubbyDutta)
