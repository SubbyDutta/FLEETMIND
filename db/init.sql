CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

-- live driver projection (updated from gps.pings)
CREATE TABLE drivers (
                         id            TEXT PRIMARY KEY,
                         name          TEXT NOT NULL,
                         status        TEXT NOT NULL,              -- IDLE / TO_PICKUP / TO_DROP / OFFLINE
                         location      geography(Point,4326),      -- PostGIS point (lng lat)
                         speed_kmph    DOUBLE PRECISION,
                         updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX drivers_geo_gix ON drivers USING GIST (location);   -- KNN nearest-driver
CREATE INDEX drivers_status_ix ON drivers (status);

CREATE TABLE orders (
                        id                TEXT PRIMARY KEY,
                        customer_name     TEXT NOT NULL,
                        restaurant        TEXT NOT NULL,
                        pickup            geography(Point,4326) NOT NULL,
                        dropoff           geography(Point,4326) NOT NULL,
                        status            TEXT NOT NULL,
                        assigned_driver   TEXT,   -- no FK: orders & drivers are independent projections (join at read time)
                        sla_deadline      TIMESTAMPTZ NOT NULL,
                        promised_eta      TIMESTAMPTZ,
                        current_eta       TIMESTAMPTZ,
                        created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
                        updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX orders_status_ix ON orders (status);
CREATE INDEX orders_driver_ix ON orders (assigned_driver);

CREATE TABLE alerts (
                        id            BIGSERIAL PRIMARY KEY,
                        order_id      TEXT,
                        driver_id     TEXT,
                        type          TEXT NOT NULL,              -- SLA_BREACH / IDLE_DRIVER / STUCK
                        severity      TEXT NOT NULL,              -- LOW / MED / HIGH
                        reason        TEXT NOT NULL,
                        resolved      BOOLEAN NOT NULL DEFAULT false,
                        created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX alerts_open_ix ON alerts (resolved, created_at DESC);
CREATE UNIQUE INDEX alerts_open_dedup_ix ON alerts (order_id, type) WHERE resolved = false;
CREATE UNIQUE INDEX alerts_open_driver_dedup_ix
    ON alerts (driver_id, type)
    WHERE resolved = false AND order_id IS NULL;

-- TRANSACTIONAL OUTBOX (the senior pattern)
CREATE TABLE outbox (
                        id            UUID PRIMARY KEY,
                        aggregate     TEXT NOT NULL,              -- 'order'
                        aggregate_id  TEXT NOT NULL,
                        event_type    TEXT NOT NULL,              -- 'DispatchAction'
                        payload       JSONB NOT NULL,
                        published     BOOLEAN NOT NULL DEFAULT false,
                        created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX outbox_unpublished_ix ON outbox (published, created_at) WHERE published = false;

-- RAG store for runbooks / SOPs (768 dims = Gemini text-embedding-004)
CREATE TABLE knowledge_chunks (
                                  id          BIGSERIAL PRIMARY KEY,
                                  doc_id      TEXT NOT NULL,
                                  chunk_no    INT NOT NULL,
                                  content     TEXT NOT NULL,
                                  tsv         tsvector GENERATED ALWAYS AS (to_tsvector('english', content)) STORED,
                                  embedding   vector(768),
                                  metadata    JSONB,
                                  created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX kc_embedding_ix ON knowledge_chunks USING hnsw (embedding vector_cosine_ops);
CREATE INDEX kc_tsv_ix ON knowledge_chunks USING GIN (tsv);

-- consumed-message dedupe for idempotent consumers
CREATE TABLE processed_events (
                                  event_id    TEXT PRIMARY KEY,
                                  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);