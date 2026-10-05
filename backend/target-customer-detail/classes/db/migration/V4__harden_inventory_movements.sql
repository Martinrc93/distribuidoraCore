ALTER TABLE inventory.stock_movements
    ADD COLUMN reason VARCHAR(500) NOT NULL DEFAULT 'Migración inicial';

-- Existing V3 rows are immutable. Validate them before installing the final constraints.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM inventory.stock_movements
        WHERE movement_type NOT IN ('SALE', 'SALE_CANCELLATION', 'MANUAL_ENTRY', 'MANUAL_ADJUSTMENT', 'RETURN')
           OR quantity = 0
           OR mod(quantity * 2, 1) <> 0
    ) THEN
        RAISE EXCEPTION 'Pre-V4 stock movements violate the approved movement type or quantity rules';
    END IF;
END
$$;

ALTER TABLE inventory.stock_movements
    ADD CONSTRAINT ck_stock_movement_type
    CHECK (movement_type IN ('SALE', 'SALE_CANCELLATION', 'MANUAL_ENTRY', 'MANUAL_ADJUSTMENT', 'RETURN'));

ALTER TABLE inventory.stock_movements
    ADD CONSTRAINT ck_stock_movement_quantity
    CHECK (quantity <> 0 AND mod(quantity * 2, 1) = 0);

CREATE INDEX ix_stock_movements_product_created_at
    ON inventory.stock_movements (product_id, created_at DESC);

ALTER TABLE inventory.stock_movements
    ALTER COLUMN reason DROP DEFAULT;
