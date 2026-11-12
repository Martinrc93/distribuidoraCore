-- A row means the product's price in that list mirrors the default list (GENERAL):
-- every General price change is written to the list as well, until the user sets a
-- price of its own for that product (which deletes the row).
CREATE TABLE catalog.price_list_follows (
    price_list_id UUID NOT NULL REFERENCES catalog.price_lists (id) ON DELETE CASCADE,
    product_id UUID NOT NULL REFERENCES catalog.products (id) ON DELETE CASCADE,
    review_status VARCHAR(20) NOT NULL,
    warning_acknowledged BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    reviewed_by UUID,
    reviewed_at TIMESTAMPTZ,
    PRIMARY KEY (price_list_id, product_id),
    CONSTRAINT ck_price_list_follows_review_status CHECK (review_status IN ('PENDING', 'CONFIRMED'))
);

CREATE INDEX ix_price_list_follows_product ON catalog.price_list_follows (product_id);
