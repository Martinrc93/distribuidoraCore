CREATE TEMPORARY TABLE inventory_balance_totals ON COMMIT DROP AS
SELECT product_id, SUM(quantity) AS quantity, MAX(updated_at) AS updated_at
FROM inventory.inventory_balances
GROUP BY product_id;

ALTER TABLE inventory.inventory_balances
    DROP CONSTRAINT fk_inventory_balances_depot,
    DROP CONSTRAINT pk_inventory_balances,
    DROP COLUMN depot_id;

DELETE FROM inventory.inventory_balances;

INSERT INTO inventory.inventory_balances(product_id, quantity, updated_at)
SELECT product_id, quantity, updated_at
FROM inventory_balance_totals;

ALTER TABLE inventory.inventory_balances
    ADD CONSTRAINT pk_inventory_balances PRIMARY KEY (product_id);

ALTER TABLE inventory.stock_movements
    DROP CONSTRAINT fk_stock_movements_depot,
    DROP COLUMN depot_id;

ALTER TABLE orders.orders
    DROP CONSTRAINT fk_orders_depot,
    DROP COLUMN depot_id;

ALTER TABLE sale.sales
    DROP CONSTRAINT fk_sales_depot,
    DROP COLUMN depot_id;

DROP TABLE inventory.depots;
