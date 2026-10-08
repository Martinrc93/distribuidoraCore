-- Preserve recorded percentages, totals, payments and balances on existing orders and sales.
ALTER TABLE orders.orders DROP COLUMN order_discount_rule_id;
ALTER TABLE sale.sales DROP COLUMN order_discount_rule_id;
ALTER TABLE orders.order_items DROP COLUMN discount_rule_id;
ALTER TABLE sale.sale_items DROP COLUMN discount_rule_id;

DROP TABLE catalog.commercial_discount_rules;
