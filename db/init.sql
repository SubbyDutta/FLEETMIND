CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE tenants (
                         id         TEXT PRIMARY KEY,
                         name       TEXT NOT NULL,
                         created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE app_users (
                           id            BIGSERIAL PRIMARY KEY,
                           tenant_id     TEXT NOT NULL REFERENCES tenants(id),
                           email         TEXT NOT NULL UNIQUE,
                           password_hash TEXT NOT NULL,
                           roles         TEXT NOT NULL,
                           created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO tenants (id, name) VALUES
                                   ('acme',   'Acme Deliveries'),
                                   ('globex', 'Globex Foods');

-- live driver projection (updated from gps.pings)
CREATE TABLE drivers (
                         id            TEXT PRIMARY KEY,
                         tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
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
                        tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
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
                        tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
                        order_id      TEXT,
                        driver_id     TEXT,
                        type          TEXT NOT NULL,              -- SLA_BREACH / IDLE_DRIVER / STUCK
                        severity      TEXT NOT NULL,              -- LOW / MED / HIGH
                        reason        TEXT NOT NULL,
                        resolved      BOOLEAN NOT NULL DEFAULT false,
                        created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX alerts_open_ix ON alerts (resolved, created_at DESC);
DROP INDEX IF EXISTS alerts_open_dedup_ix;
DROP INDEX IF EXISTS alerts_open_driver_dedup_ix;
CREATE UNIQUE INDEX alerts_open_dedup_ix
    ON alerts (tenant_id, order_id, type) WHERE resolved = false;
CREATE UNIQUE INDEX alerts_open_driver_dedup_ix
    ON alerts (tenant_id, driver_id, type) WHERE resolved = false AND order_id IS NULL;

CREATE INDEX drivers_tenant_ix        ON drivers (tenant_id);
CREATE INDEX orders_tenant_status_ix  ON orders (tenant_id, status);
CREATE INDEX alerts_tenant_open_ix    ON alerts (tenant_id, resolved, created_at DESC);
-- TRANSACTIONAL OUTBOX (the senior pattern)
CREATE TABLE outbox (
                        id            UUID PRIMARY KEY,
                        tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
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
                                  tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
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
CREATE INDEX kc_tenant_doc_ix ON knowledge_chunks (tenant_id, doc_id);

-- consumed-message dedupe for idempotent consumers
CREATE TABLE processed_events (
                                  event_id    TEXT PRIMARY KEY,
                                  processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO drivers (id, name, status, location, speed_kmph, tenant_id) VALUES
    ('gx-driver-1', 'Gita', 'IDLE',
     ST_SetSRID(ST_MakePoint(88.3600, 22.5700), 4326)::geography, 0, 'globex');

INSERT INTO orders (id, customer_name, restaurant, pickup, dropoff,
                    status, assigned_driver, sla_deadline, tenant_id) VALUES
    ('gx-order-1', 'Globex Cafeteria', 'Globex Kitchen',
     ST_SetSRID(ST_MakePoint(88.3500, 22.5600), 4326)::geography,
     ST_SetSRID(ST_MakePoint(88.3700, 22.5800), 4326)::geography,
     'ASSIGNED', 'gx-driver-1', now() + interval '45 minutes', 'globex');

INSERT INTO alerts (order_id, driver_id, type, severity, reason, tenant_id) VALUES
    ('gx-order-1', 'gx-driver-1', 'SLA_BREACH', 'HIGH', 'Globex order running late', 'globex');
-- P19 notification service
CREATE TABLE notification_preferences (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
    email         TEXT NOT NULL,
    event_type    TEXT NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    inapp_enabled BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (tenant_id, email, event_type)
);

CREATE TABLE notifications (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
    recipient   TEXT NOT NULL,
    event_type  TEXT NOT NULL,
    subject     TEXT NOT NULL,
    body        TEXT NOT NULL,
    is_read     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX notifications_recipient_ix ON notifications (recipient, is_read, created_at DESC);

CREATE TABLE notification_dedupe (
    event_id     TEXT PRIMARY KEY,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

INSERT INTO notification_preferences (tenant_id, email, event_type, email_enabled, inapp_enabled) VALUES
    ('acme',   'dispatcher@acme.com',   'ALERT',           true,  true),
    ('acme',   'dispatcher@acme.com',   'ORDER_STATUS',    true,  true),
    ('acme',   'dispatcher@acme.com',   'DISPATCH_ACTION', true,  true),
    ('acme',   'admin@acme.com',        'ALERT',           true,  false),
    ('acme',   'viewer@acme.com',       'ALERT',           false, true),
    ('acme',   'viewer@acme.com',       'ORDER_STATUS',    false, true),
    ('globex', 'dispatcher@globex.com', 'ALERT',           true,  true);
