-- Purchases (supplier orders) get a lifecycle. A CONFIRMED purchase adds its quantities to stock;
-- editing or cancelling it records compensating movements. Orders registered before this migration
-- never touched stock, so they become LEGACY and keep that behavior (no stock effect when edited
-- or cancelled), avoiding double counting merchandise that may have been adjusted manually.
ALTER TABLE purchasing.supplier_orders
    ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'LEGACY',
    ADD COLUMN updated_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN confirmed_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN confirmed_by UUID REFERENCES identity.users(id),
    ADD COLUMN cancelled_at TIMESTAMP WITH TIME ZONE,
    ADD COLUMN cancelled_by UUID REFERENCES identity.users(id);

ALTER TABLE purchasing.supplier_orders ALTER COLUMN status SET DEFAULT 'DRAFT';

ALTER TABLE purchasing.supplier_orders
    ADD CONSTRAINT ck_supplier_orders_status CHECK (status IN ('LEGACY', 'DRAFT', 'CONFIRMED', 'CANCELLED'));

CREATE INDEX ix_supplier_orders_status ON purchasing.supplier_orders(status, order_date DESC);

ALTER TABLE inventory.stock_movements DROP CONSTRAINT ck_stock_movement_type;
ALTER TABLE inventory.stock_movements
    ADD CONSTRAINT ck_stock_movement_type
    CHECK (movement_type IN ('SALE', 'SALE_CANCELLATION', 'MANUAL_ENTRY', 'MANUAL_ADJUSTMENT', 'RETURN',
                             'TRANSFER_OUT', 'TRANSFER_IN', 'PURCHASE', 'PURCHASE_ADJUSTMENT', 'PURCHASE_CANCELLATION'));
