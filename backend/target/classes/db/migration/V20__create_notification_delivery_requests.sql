CREATE TABLE notification.delivery_requests (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders.orders (id) ON DELETE CASCADE,
    sale_id UUID NOT NULL REFERENCES sale.sales (id) ON DELETE CASCADE,
    channel VARCHAR(20) NOT NULL,
    document_format VARCHAR(20) NOT NULL,
    recipient VARCHAR(320) NOT NULL,
    idempotency_key VARCHAR(120) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'QUEUED',
    requested_by UUID NULL REFERENCES identity.users (id) ON DELETE SET NULL,
    outbox_event_id UUID NULL UNIQUE REFERENCES notification.outbox_events (id) ON DELETE SET NULL,
    attempt_count INTEGER NOT NULL DEFAULT 0,
    last_error VARCHAR(1000) NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ NULL,
    CONSTRAINT ck_notification_channel CHECK (channel IN ('EMAIL', 'WHATSAPP')),
    CONSTRAINT ck_notification_format CHECK (document_format IN ('A4', 'TICKET')),
    CONSTRAINT ck_notification_status CHECK (status IN ('QUEUED', 'SENDING', 'SENT', 'FAILED', 'RETRY_EXHAUSTED')),
    CONSTRAINT ck_notification_attempt_count CHECK (attempt_count >= 0)
);

CREATE INDEX ix_notification_requests_sale ON notification.delivery_requests (sale_id, created_at DESC);
