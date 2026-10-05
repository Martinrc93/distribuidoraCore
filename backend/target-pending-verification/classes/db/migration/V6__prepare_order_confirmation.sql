ALTER TABLE orders.orders
    ADD COLUMN idempotency_key VARCHAR(100) NULL,
    ADD COLUMN idempotency_fingerprint VARCHAR(64) NULL;

CREATE UNIQUE INDEX ux_orders_idempotency_key
    ON orders.orders (idempotency_key)
    WHERE idempotency_key IS NOT NULL;

ALTER TABLE orders.order_items
    ADD COLUMN price_list_id UUID NULL,
    ADD COLUMN price_list_code VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN line_discount_percent NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE sale.sale_items
    ADD COLUMN price_list_id UUID NULL,
    ADD COLUMN price_list_code VARCHAR(40) NOT NULL DEFAULT 'GENERAL',
    ADD COLUMN line_discount_percent NUMERIC(19,4) NOT NULL DEFAULT 0;

ALTER TABLE orders.order_items
    ALTER COLUMN price_list_code DROP DEFAULT,
    ALTER COLUMN line_discount_percent DROP DEFAULT;

ALTER TABLE sale.sale_items
    ALTER COLUMN price_list_code DROP DEFAULT,
    ALTER COLUMN line_discount_percent DROP DEFAULT;

CREATE TABLE customer.account_ledger (
    id UUID PRIMARY KEY,
    customer_id UUID NOT NULL REFERENCES customer.customers (id),
    sale_id UUID NOT NULL REFERENCES sale.sales (id),
    entry_type VARCHAR(10) NOT NULL,
    amount NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_account_ledger_entry_type CHECK (entry_type IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ck_account_ledger_amount CHECK (amount > 0)
);

CREATE INDEX ix_account_ledger_customer_created_at
    ON customer.account_ledger (customer_id, created_at DESC);
