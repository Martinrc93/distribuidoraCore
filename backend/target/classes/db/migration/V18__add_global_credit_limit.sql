CREATE SCHEMA IF NOT EXISTS app;

CREATE TABLE app.business_settings (
    id SMALLINT PRIMARY KEY CHECK (id = 1),
    credit_limit NUMERIC(19,4) NULL,
    updated_by UUID NULL REFERENCES identity.users (id) ON DELETE SET NULL,
    updated_at TIMESTAMPTZ NULL,
    CONSTRAINT ck_business_settings_credit_limit CHECK (credit_limit IS NULL OR credit_limit >= 0)
);

INSERT INTO app.business_settings (id, credit_limit) VALUES (1, NULL);

ALTER TABLE orders.orders
    ADD COLUMN credit_limit_exceeded BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN credit_limit_snapshot NUMERIC(19,4) NULL,
    ADD COLUMN projected_balance_snapshot NUMERIC(19,4) NULL;
