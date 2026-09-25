CREATE TABLE catalog.product_price_history (
    id UUID PRIMARY KEY,
    price_list_id UUID NOT NULL REFERENCES catalog.price_lists (id),
    product_id UUID NOT NULL REFERENCES catalog.products (id),
    price NUMERIC(19,4) NOT NULL,
    effective_on DATE NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_product_price_history_amount CHECK (price >= 0)
);

CREATE INDEX ix_product_price_history_lookup
    ON catalog.product_price_history (price_list_id, product_id, effective_on DESC, created_at DESC);

-- Existing rows establish the price known when history tracking begins.
INSERT INTO catalog.product_price_history
    (id, price_list_id, product_id, price, effective_on, created_at, updated_at)
SELECT gen_random_uuid(), price_list_id, product_id, price,
       (CURRENT_TIMESTAMP AT TIME ZONE 'America/Argentina/Buenos_Aires')::date,
       CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM catalog.product_prices;
