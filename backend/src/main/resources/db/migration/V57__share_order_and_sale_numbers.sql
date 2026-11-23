ALTER TABLE sale.sales ADD COLUMN legacy_sale_number VARCHAR(30);

UPDATE sale.sales SET legacy_sale_number = sale_number;

-- Flyway runs this migration in one transaction. Rebuild uniqueness after the
-- update so even legacy numbers swapped between orders can be reassigned.
ALTER TABLE sale.sales DROP CONSTRAINT sales_sale_number_key;

UPDATE sale.sales s
SET sale_number = o.order_number
FROM orders.orders o
WHERE o.id = s.order_id;

ALTER TABLE sale.sales ADD CONSTRAINT sales_sale_number_key UNIQUE (sale_number);
