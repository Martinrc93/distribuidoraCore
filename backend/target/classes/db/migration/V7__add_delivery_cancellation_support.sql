ALTER TABLE orders.orders
    ADD COLUMN delivered_at TIMESTAMPTZ NULL,
    ADD COLUMN cancelled_at TIMESTAMPTZ NULL;

ALTER TABLE sale.sales
    ADD COLUMN delivered_at TIMESTAMPTZ NULL,
    ADD COLUMN cancelled_at TIMESTAMPTZ NULL;

CREATE TABLE orders.delivery_attempts (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders.orders (id),
    attempt_number INTEGER NOT NULL,
    result VARCHAR(20) NOT NULL,
    observation TEXT NULL,
    attempted_by UUID NOT NULL REFERENCES identity.users (id),
    attempted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_delivery_attempt_number CHECK (attempt_number > 0),
    CONSTRAINT ck_delivery_attempt_result CHECK (result IN ('DELIVERED', 'FAILED')),
    CONSTRAINT ck_delivery_attempt_failed_observation CHECK (
        result <> 'FAILED'
        OR (observation IS NOT NULL AND btrim(observation) <> '')
    ),
    CONSTRAINT ux_delivery_attempt_order_number UNIQUE (order_id, attempt_number)
);

CREATE INDEX ix_delivery_attempts_order_time
    ON orders.delivery_attempts (order_id, attempted_at DESC);
