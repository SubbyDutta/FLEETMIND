CREATE TABLE IF NOT EXISTS notification_preferences (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
    email         TEXT NOT NULL,
    event_type    TEXT NOT NULL,
    email_enabled BOOLEAN NOT NULL DEFAULT true,
    inapp_enabled BOOLEAN NOT NULL DEFAULT true,
    UNIQUE (tenant_id, email, event_type)
);

CREATE TABLE IF NOT EXISTS notifications (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   TEXT NOT NULL DEFAULT 'acme' REFERENCES tenants(id),
    recipient   TEXT NOT NULL,
    event_type  TEXT NOT NULL,
    subject     TEXT NOT NULL,
    body        TEXT NOT NULL,
    is_read     BOOLEAN NOT NULL DEFAULT false,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS notifications_recipient_ix ON notifications (recipient, is_read, created_at DESC);

CREATE TABLE IF NOT EXISTS notification_dedupe (
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
    ('globex', 'dispatcher@globex.com', 'ALERT',           true,  true)
ON CONFLICT (tenant_id, email, event_type) DO NOTHING;
