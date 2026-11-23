ALTER TABLE purchasing.supplier_orders DROP CONSTRAINT ck_supplier_orders_status;
ALTER TABLE purchasing.supplier_orders ALTER COLUMN status SET DEFAULT 'PENDING';

UPDATE purchasing.supplier_orders SET status = 'PENDING' WHERE status = 'DRAFT';

ALTER TABLE purchasing.supplier_orders
    ADD CONSTRAINT ck_supplier_orders_status CHECK (status IN ('LEGACY', 'PENDING', 'CONFIRMED', 'CANCELLED'));
