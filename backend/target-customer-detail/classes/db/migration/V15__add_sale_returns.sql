ALTER TABLE sale.sale_items
    ADD CONSTRAINT ux_sale_items_id_sale UNIQUE (id, sale_id),
    ADD CONSTRAINT ux_sale_items_id_product UNIQUE (id, product_id);

CREATE TABLE sale.returns (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES sale.sales (id),
    returned_by UUID NOT NULL REFERENCES identity.users (id),
    reason VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_sale_return_reason CHECK (btrim(reason) <> ''),
    CONSTRAINT ux_sale_returns_id_sale UNIQUE (id, sale_id)
);

CREATE INDEX ix_sale_returns_sale_created_at
    ON sale.returns (sale_id, created_at DESC);

CREATE TABLE sale.return_items (
    id UUID PRIMARY KEY,
    return_id UUID NOT NULL REFERENCES sale.returns (id),
    sale_id UUID NOT NULL,
    sale_item_id UUID NOT NULL REFERENCES sale.sale_items (id),
    product_id UUID NOT NULL REFERENCES catalog.products (id),
    quantity NUMERIC(19,4) NOT NULL,
    CONSTRAINT fk_sale_return_item_return_sale FOREIGN KEY (return_id, sale_id)
        REFERENCES sale.returns (id, sale_id),
    CONSTRAINT fk_sale_return_item_line_sale FOREIGN KEY (sale_item_id, sale_id)
        REFERENCES sale.sale_items (id, sale_id),
    CONSTRAINT fk_sale_return_item_line_product FOREIGN KEY (sale_item_id, product_id)
        REFERENCES sale.sale_items (id, product_id),
    CONSTRAINT ck_sale_return_item_quantity CHECK (
        quantity > 0 AND mod(quantity * 2, 1) = 0
    ),
    CONSTRAINT ux_sale_return_item_per_line UNIQUE (return_id, sale_item_id)
);

CREATE INDEX ix_sale_return_items_sale_item_id
    ON sale.return_items (sale_item_id);
