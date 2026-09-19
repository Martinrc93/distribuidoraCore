CREATE TABLE catalog.price_lists (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_price_list_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_price_list_default_active CHECK (NOT is_default OR status = 'ACTIVE')
);

CREATE UNIQUE INDEX ux_price_lists_default
    ON catalog.price_lists (is_default)
    WHERE is_default;

CREATE TABLE catalog.product_prices (
    price_list_id UUID NOT NULL REFERENCES catalog.price_lists (id),
    product_id UUID NOT NULL REFERENCES catalog.products (id),
    price NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (price_list_id, product_id),
    CONSTRAINT ck_product_price_amount CHECK (price >= 0)
);

CREATE INDEX ix_product_prices_product_id
    ON catalog.product_prices (product_id);

ALTER TABLE customer.customers
    ADD COLUMN price_list_id UUID NULL,
    ADD CONSTRAINT fk_customers_price_list
        FOREIGN KEY (price_list_id) REFERENCES catalog.price_lists (id);

INSERT INTO catalog.price_lists (id, code, name, status, is_default, created_at, updated_at)
VALUES
    ('00000000-0000-0000-0000-000000000001', 'GENERAL', 'Lista general', 'ACTIVE', TRUE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000002', 'LISTA_2', 'Lista 2', 'ACTIVE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP),
    ('00000000-0000-0000-0000-000000000003', 'LISTA_3', 'Lista 3', 'ACTIVE', FALSE, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP);

INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
SELECT price_lists.id, products.id, products.price, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM catalog.price_lists
CROSS JOIN catalog.products
WHERE price_lists.code IN ('GENERAL', 'LISTA_2', 'LISTA_3');
