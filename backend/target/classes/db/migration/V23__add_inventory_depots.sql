CREATE TABLE inventory.depots (
    id UUID PRIMARY KEY,
    code VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(120) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_depot_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX ux_depots_single_default ON inventory.depots (is_default) WHERE is_default;

INSERT INTO inventory.depots(id, code, name, status, is_default, created_at, updated_at)
VALUES ('00000000-0000-0000-0000-000000000001', 'CENTRAL', 'Depósito central', 'ACTIVE', TRUE, now(), now());

ALTER TABLE inventory.inventory_balances
    ADD COLUMN depot_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001';

ALTER TABLE inventory.inventory_balances
    DROP CONSTRAINT inventory_balances_pkey;

ALTER TABLE inventory.inventory_balances
    ADD CONSTRAINT pk_inventory_balances PRIMARY KEY (depot_id, product_id),
    ADD CONSTRAINT fk_inventory_balances_depot FOREIGN KEY (depot_id) REFERENCES inventory.depots (id);

ALTER TABLE inventory.stock_movements
    ADD COLUMN depot_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    ADD CONSTRAINT fk_stock_movements_depot FOREIGN KEY (depot_id) REFERENCES inventory.depots (id),
    DROP CONSTRAINT ck_stock_movement_type,
    ADD CONSTRAINT ck_stock_movement_type
        CHECK (movement_type IN ('SALE', 'SALE_CANCELLATION', 'MANUAL_ENTRY', 'MANUAL_ADJUSTMENT', 'RETURN', 'TRANSFER_OUT', 'TRANSFER_IN'));

CREATE INDEX ix_stock_movements_depot_product_created_at
    ON inventory.stock_movements (depot_id, product_id, created_at DESC);

ALTER TABLE orders.orders
    ADD COLUMN depot_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    ADD CONSTRAINT fk_orders_depot FOREIGN KEY (depot_id) REFERENCES inventory.depots (id);

ALTER TABLE sale.sales
    ADD COLUMN depot_id UUID NOT NULL DEFAULT '00000000-0000-0000-0000-000000000001',
    ADD CONSTRAINT fk_sales_depot FOREIGN KEY (depot_id) REFERENCES inventory.depots (id);
