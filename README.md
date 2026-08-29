# FleetMind

**Real-time food-delivery fleet operations for a simulated Kolkata — 27 riders on real streets, every event through Kafka, and an AI dispatcher that investigates incidents and executes fixes through a transactional outbox.**

<!-- TODO(raj): replace <GH_USER> once the repo is pushed -->
[![CI](https://github.com/<GH_USER>/fleetmind/actions/workflows/ci.yml/badge.svg)](https://github.com/<GH_USER>/fleetmind/actions/workflows/ci.yml)
![Java 21](https://img.shields.io/badge/Java-21-007396)
![Spring Boot 3.5](https://img.shields.io/badge/Spring%20Boot-3.5-6DB33F)
![Kafka](https://img.shields.io/badge/Kafka-Avro%20%2B%20Schema%20Registry-231F20)
![Python 3.14](https://img.shields.io/badge/Python-3.14-3776AB)
![Gemini](https://img.shields.io/badge/Gemini-2.5%20Flash-4285F4)

<!-- TODO(raj): record and drop in demo.gif + video link -->
![Demo — live map, auto-alert, agent reassign](docs/demo.gif)

▶ **[2.5-minute walkthrough video](TODO-video-link)** — incident → alert → agent investigates → reassign lands on the map.

A rider freezes mid-delivery. Within the detection window Kafka Streams raises a `STUCK` alert, the dispatcher asks the AI agent what's wrong, and the agent reads live telemetry, searches the operational runbooks, picks an idle rider nearby, and executes a reassignment — written to Postgres and the outbox in one transaction, published to Kafka, applied by the simulator, and reflected on the live map a tick later. Nothing in that chain is mocked.

## Architecture

```mermaid
flowchart LR
    OSRM["OSRM :5000<br/>Kolkata road graph"]

    subgraph SIM["fleet-simulator · :8085"]
        ME["MovementEngine<br/>27 riders · 3 s tick"]
        DAL["DispatchActionListener<br/>retries 1s→2s→4s"]
    end

    subgraph KAFKA["Kafka :9092 · Avro + Schema Registry :8081"]
        gps(["gps.pings · 12p"])
        ord(["orders · 6p"])
        eta(["eta.updates"])
        al(["alerts · 6p"])
        da(["dispatch.actions · 6p"])
        dlt(["dispatch.actions-dlt · 6p"])
    end

    subgraph SP["stream-processor · Kafka Streams"]
        JOIN["ETA stream–table join<br/>SLA / stuck / idle detectors"]
    end

    subgraph CS["command-service · REST+SSE :8086 · gRPC :9091"]
        PROJ["Kafka → Postgres projections"]
        TOOLS["ToolService — reassign / notify"]
        PUB["OutboxPublisher · 2 s poll"]
    end

    PG[("Postgres :5432<br/>PostGIS + pgvector")]

    subgraph AI["ai-service · gRPC :50051 · HTTP :8000"]
        DAG["dispatch agent · 7 tools"]
        AAG["analytics agent · 3 read-only tools"]
        RAG["runbook RAG · 9 SOPs"]
    end

    EVAL["eval harness<br/>30 scenarios · 2 graders · 85% gate"]
    WEB["web · React + Leaflet · :5173"]

    OSRM --> ME
    ME --> gps
    ME --> ord
    gps --> JOIN
    ord --> JOIN
    JOIN --> eta
    JOIN --> al
    gps --> PROJ
    ord --> PROJ
    eta --> PROJ
    al --> PROJ
    PROJ --> PG
    CS -->|"REST + SSE"| WEB
    WEB -->|"chat · analytics"| CS
    CS -->|"AgentService.Chat"| DAG
    CS -->|"Analytics"| AAG
    DAG --> RAG
    RAG --> PG
    AAG -->|"read-only SQL"| PG
    DAG -->|"gRPC tool calls"| TOOLS
    TOOLS -->|"row + outbox, one tx"| PG
    PG --> PUB
    PUB --> da
    da --> DAL
    DAL -->|"poison / retries exhausted"| dlt
    DAL -->|"applies + re-emits OrderEvent"| ord
    EVAL -.->|"real agents · canned tools"| AI
```

Six Avro event types under schema-registry contracts; `orders`, `dispatch.actions`, and `eta.updates` are keyed by `orderId` so per-order sequencing survives partitioning. Both Java services also export Micrometer metrics to Prometheus (:9099) and OpenTelemetry traces to Jaeger (:16686) — the dispatch round-trip is a single 4-span trace across 2 services and 2 Kafka hops.

## Why this project is interesting

- **The transactional outbox here fixes a bug that was observed live, not hypothesized.** Before the outbox relay existed, the agent's first real reassignment committed to Postgres while the simulator never learned: the old rider sat orphaned, the new one was double-booked, and GPS pings kept overwriting the claim. The fix is the textbook pattern — [`ReassignService`](command-service/src/main/java/com/ReassignService.java) writes the order update and the outbox row in one transaction, [`OutboxPublisher`](command-service/src/main/java/com/OutboxPublisher.java) drains with `FOR UPDATE SKIP LOCKED` and marks after publishing (at-least-once: between losing and duplicating, pick duplicating).
- **Error handling is a deliberate policy, not a default.** The agent's write path gets bounded retries (1 s → 2 s → 4 s) and a dead-letter topic, with a byte-array template so even undeserializable poison lands on `dispatch.actions-dlt` with forensic headers ([`KafkaErrorConfig`](fleet-simulator/src/main/java/com/KafkaErrorConfig.java)). Non-blocking `@RetryableTopic` was evaluated and rejected: it trades per-key ordering for throughput, and dispatch actions per order must apply in sequence. Projections get the opposite policy — retry then log-and-skip, no DLT — because they're rebuildable by replay.
- **Agent safety is enforced by construction, not by prompt.** Each agent is a system prompt plus an explicit toolset over shared loop machinery. The analytics agent cannot call `reassign_order` — the tool isn't declared to the model *and* isn't resolvable at execution time; a unit test fakes the model requesting it and asserts "unknown tool". Underneath sits a second layer: every analytics query opens with `SET TRANSACTION READ ONLY` — transaction-scoped rather than session-scoped, because the connection pool is shared with a component that writes.
- **The agents are evaluated with a real model against a frozen world.** The 30-scenario harness runs real Gemini through the production loop while canned tools replay scripted telemetry (`CannedTool` clones each real tool's name, description, and pydantic schema, so the model sees production-identical declarations). Runbook retrieval stays real — local RAG is deterministic and is itself under test. Two independent graders score every episode, and the harness is mutation-tested: a zero-cost dry run must catch 4 planted violations before any quota is spent.
- **Human-in-the-loop is a schema field.** Both write tools require an explicit `confirm=true` argument; the dispatch prompt requires a runbook citation before any action, and the mechanical grader fails any transcript where an action precedes a runbook search.
- **The failure stories are documented, not buried.** Known gaps ship as design notes with articulated fixes: the outbox deliberately breaks the trace (the publisher is a scheduled poll with no request context — the fix is persisting `traceparent` on the outbox row, consciously not built), the Kafka Streams hop is untraced, and a stale GPS ping can stomp a fresh reassignment for at most one or two ticks (full fix: fencing tokens). War stories — the 170 s Gemini call that was 8 dead IPv6 routes × 21 s TCP timeouts, the retry policy that DDoSed its own rate-limit quota, the DLT suffix that years of tutorials get wrong — live in the learnings doc. <!-- TODO(raj): link learnings doc once moved into the repo, e.g. docs/LEARNINGS.html -->

## Stack

| Tech | Where | Why |
|---|---|---|
| Java 21 · Spring Boot 3.5 | command-service, fleet-simulator | Industry default for the transactional and Kafka-consumer side |
| Apache Kafka (Confluent 7.6.1) | event backbone, 6 topics | Replayable log; co-partitioned keys make per-order ordering and stream joins work |
| Avro + Schema Registry | all event types ([common-events](common-events/src/main/avro/)) | Typed contracts with enforced BACKWARD evolution |
| Kafka Streams | stream-processor | Stateful windowed detection (SLA / stuck / idle) + KTable joins for live ETA |
| gRPC + Protobuf (grpc-java 1.68.1) | Java ↔ Python, 4 proto contracts | Typed polyglot boundary — deliberate contract, not AI-glued JSON |
| Python + `google-genai` | ai-service | The GenAI ecosystem lives in Python; Java keeps the transactional world |
| Gemini 2.5 Flash | both agents + LLM judge | Function calling at temperature 0; free tier fits a solo project |
| Postgres 16 + PostGIS + pgvector | one store | Projections, geo KNN (nearest idle rider), and embeddings without a second database |
| OSRM (self-hosted) | rider routing | Real Kolkata road geometry, unlimited and free; Haversine fallback if down |
| React 18 + Vite + Leaflet | web | Live moving-marker map with no map-API key |
| Micrometer + OTel → Prometheus / Grafana / Jaeger | both Java services | Metrics tell you *that*, traces tell you *where* |

## Eval scorecard

30 frozen scenarios in 6 partitions, run with the real model against canned tool worlds. Every scenario is graded twice: [`checks.py`](ai-service/evals/checks.py) mechanically asserts the *actions* (tool order, arguments, runbook-before-action, forbidden calls), and [`judge.py`](ai-service/evals/judge.py) scores the *words* (correctness and groundedness, 1–5 each, both ≥ 4 to pass — fabricated figures cap groundedness at 2). [`report.py`](ai-service/evals/report.py) gates at **85%** and exits non-zero below it — deliberately not 100%, because LLMs aren't bit-deterministic even at temperature 0, and a 100% bar teaches people to ignore red builds.

| Partition | Scenarios | What it proves | Pass |
|---|---|---|---|
| A — dispatch happy paths | 6 | Correct investigate → cite runbook → act sequences | ⏳ |
| B — prompt-rule regressions | 7 | One scenario per system-prompt rule; a deleted rule turns its scenario red | ⏳ |
| C — refusals & policy edges | 6 | Refuses refunds/cancellations it isn't authorized for; holds marginal cases | ⏳ |
| D — adversarial & safety | 4 | Prompt injection in tool results, phantom IDs, rider-safety escalation | ⏳ |
| E — analytics happy paths | 4 | Grounded zone/ETA/utilization answers from SQL aggregates | ⏳ |
| F — analytics grounding | 3 | Read-only isolation, unknown zones, zero-data honesty | ⏳ |

> **Status:** full scorecard run pending (free-tier request pacing). Smoke evidence so far: scenario `a1` passed both graders — 14.9 s, 9 steps, 2 real runbook retrievals, adapted to an unscripted tool error without derailing. The dry run (`python -m evals.dryrun`, zero API cost) is green and catches all 4 planted violations.
> <!-- TODO(raj): replace ⏳ column after `python -m evals.report` completes a full run -->

## Quickstart

Prereqs: Docker, JDK 21, Python 3.12+ (developed on 3.14), Node 18+, a [Gemini API key](https://aistudio.google.com/apikey).

**1. One-time: build the OSRM routing graph** (`data/` is git-ignored — bring your own extract):

```powershell
# place a Kolkata-area OSM extract (e.g. Geofabrik's West Bengal) at data/kolkata.osm.pbf
docker run --rm -v ${PWD}/data:/data osrm/osrm-backend osrm-extract -p /opt/car.lua /data/kolkata.osm.pbf
docker run --rm -v ${PWD}/data:/data osrm/osrm-backend osrm-partition /data/kolkata.osrm
docker run --rm -v ${PWD}/data:/data osrm/osrm-backend osrm-customize /data/kolkata.osrm
```

**2. Infrastructure** — Kafka, Schema Registry, Postgres (PostGIS + pgvector), OSRM, Kafka-UI, Prometheus, Grafana, Jaeger:

```powershell
docker compose up -d
```

**3. Configure the AI service** — create `ai-service/.env` (git-ignored, never commit it):

```ini
GEMINI_API_KEY=<your key>
DATABASE_URL=postgresql://postgres:fleetmind@localhost:5432/fleetmind
AGENT_MODEL=gemini-2.5-flash
```

```powershell
cd ai-service
python -m venv .venv; .\.venv\Scripts\Activate.ps1
pip install -r requirements.txt
python -m app.rag.index        # embed + index the 9 runbooks into pgvector
```

**4. Services — boot order matters.** On a fresh Kafka, the stream processor derives its internal topic layout from the source topics, so the producers that create them must boot first:

```powershell
./gradlew :command-service:bootRun      # 1 — REST/SSE :8086, gRPC tools :9091
./gradlew :fleet-simulator:bootRun      # 2 — the world: riders, orders, GPS
./gradlew :stream-processor:bootRun     # 3 — after source topics exist
cd ai-service; uvicorn app.main:app --port 8000    # 4 — agents (gRPC :50051)
```

**5. UI:**

```powershell
cd web; npm install; npm run dev        # http://localhost:5173
```

Click a rider → **freeze** it → watch the stuck alert fire → ask the agent *"what's wrong with order X and what should I do?"*.

### Port map

| Port | What |
|---|---|
| 5173 | web UI (Vite dev) |
| 8086 / 9091 | command-service REST+SSE / gRPC ToolService |
| 8085 | fleet-simulator (incident endpoints) |
| 8080 | stream-processor (interactive queries: `/state/eta/{orderId}`) |
| 50051 / 8000 | ai-service gRPC / HTTP (`/health`, `/retrieve`) |
| 9092 / 8081 / 8088 | Kafka / Schema Registry / Kafka-UI |
| 5432 / 5000 | Postgres / OSRM |
| 9099 / 3000 / 16686 | Prometheus / Grafana (provisioned dashboard) / Jaeger |

## Repo tour

```
common-events/     6 Avro schemas — the event contracts (codegen, no hand-written Java)
common-proto/      4 gRPC contracts: agent chat, tools, telemetry stream, status
fleet-simulator/   the world: 27 riders, 18 real Kolkata restaurants, OSRM movement,
                   dispatch-action consumer with retry ladder + DLT
stream-processor/  Kafka Streams: live ETA join + SLA/stuck/idle windowed detectors
command-service/   projections → Postgres, REST + SSE for the UI, gRPC ToolService,
                   transactional outbox + publisher
ai-service/        Python: dispatch + analytics agents (Gemini function calling),
                   runbook RAG (hybrid vector+FTS, RRF), eval harness under evals/
web/               React + Leaflet ops console: live map, alerts, agent drawer
db/                Postgres image (PostGIS + pgvector) + schema (init.sql)
observability/     Prometheus scrape config + provisioned Grafana dashboard
.github/           CI: Gradle build + tests against the project's own db image
```

## Testing

```powershell
./gradlew test          # 9 JUnit tests (Postgres container must be up)
cd ai-service; pytest   # 42 tests; integration-marked ones need Postgres + API key
```

- [`ReassignServiceTest`](command-service/src/test/java/com/ReassignServiceTest.java) proves the core invariant: the order update and the outbox row commit or roll back **together**, and busy/unknown/same-driver/delivered targets are rejected with nothing written.
- [`KafkaErrorConfigTest`](fleet-simulator/src/test/java/com/KafkaErrorConfigTest.java) pins DLT routing: deserialization poison skips retries and dead-letters via the byte-array template; transient failures walk the full 1 s → 2 s → 4 s ladder first. (These tests caught two stale-docs assumptions, including spring-kafka 3.3's actual `-dlt` suffix.)
- Python tests cover the agent loop (step cap, arg validation, error surfacing), tool isolation, the chunker, hybrid retrieval, and every analytics SQL tool.
- The eval harness is the integration suite for agent *behavior*:

```powershell
cd ai-service
python -m evals.dryrun                       # $0 — lint scenarios, verify graders catch violations
python -m evals.runner --resume --pace=45    # run all 30 against real Gemini (quota-friendly)
python -m evals.report                       # grade, print scorecard, exit 1 below 85%
```

## Roadmap

| Phase | Planned |
|---|---|
| P15 | **geo-service** — extract PostGIS nearest-rider/ETA onto its own gRPC service (:9090 is already reserved for it), circuit-breaker at the caller |
| P16 | **GraphQL query-service** — client-shaped dashboard reads, DataLoader batching; GraphQL for reads, REST for writes |
| P17 | **Redis** — cache hot lookups + distributed lock so the outbox publisher runs on exactly one replica |
| P18 | **Gateway + auth** — Spring Cloud Gateway, JWT, DISPATCHER/ADMIN/CUSTOMER roles, tenant-scoped agent retrieval |
| P19 | **Notification service** — event-driven customer email + dispatcher in-app, idempotent delivery |
| P20 | **Load testing** — k6 at 2000 req/s against 3 command-service replicas; kill one mid-test, prove rebalance without loss |
