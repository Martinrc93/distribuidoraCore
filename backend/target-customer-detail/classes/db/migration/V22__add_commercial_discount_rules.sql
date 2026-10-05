CREATE TABLE catalog.commercial_discount_rules (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    description VARCHAR(160) NOT NULL,
    kind VARCHAR(10) NOT NULL,
    percent NUMERIC(7,4) NOT NULL,
    customer_id UUID NULL REFERENCES customer.customers (id),
    price_list_id UUID NULL REFERENCES catalog.price_lists (id),
    product_id UUID NULL REFERENCES catalog.products (id),
    priority INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(10) NOT NULL DEFAULT 'ACTIVE',
    valid_from DATE NOT NULL,
    valid_until DATE NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_discount_rule_kind CHECK (kind IN ('LINE', 'ORDER')),
    CONSTRAINT ck_discount_rule_percent CHECK (percent > 0 AND percent <= 100),
    CONSTRAINT ck_discount_rule_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_discount_rule_dates CHECK (valid_until IS NULL OR valid_until >= valid_from),
    CONSTRAINT ck_discount_rule_scope CHECK (
        (kind = 'LINE' AND product_id IS NOT NULL)
        OR (kind = 'ORDER' AND product_id IS NULL)
    ),
    CONSTRAINT ck_discount_rule_priority CHECK (priority BETWEEN -1000 AND 1000)
);

CREATE INDEX ix_discount_rules_line_lookup
    ON catalog.commercial_discount_rules (product_id, price_list_id, customer_id, priority DESC)
    WHERE kind = 'LINE' AND status = 'ACTIVE';

CREATE INDEX ix_discount_rules_order_lookup
    ON catalog.commercial_discount_rules (price_list_id, customer_id, priority DESC)
    WHERE kind = 'ORDER' AND status = 'ACTIVE';

ALTER TABLE orders.orders
    ADD COLUMN order_discount_percent NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN order_discount_rule_id UUID NULL REFERENCES catalog.commercial_discount_rules (id);

ALTER TABLE sale.sales
    ADD COLUMN order_discount_percent NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD COLUMN order_discount_rule_id UUID NULL REFERENCES catalog.commercial_discount_rules (id);

ALTER TABLE orders.order_items
    ADD COLUMN discount_rule_id UUID NULL REFERENCES catalog.commercial_discount_rules (id);

ALTER TABLE sale.sale_items
    ADD COLUMN discount_rule_id UUID NULL REFERENCES catalog.commercial_discount_rules (id);
