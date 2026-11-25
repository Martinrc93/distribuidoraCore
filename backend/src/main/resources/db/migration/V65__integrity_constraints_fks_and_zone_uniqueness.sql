-- Integrity checks, precision hardening on commissions, cross-schema FKs, and case-insensitive zone uniqueness.

ALTER TABLE orders.orders
    ADD CONSTRAINT ck_orders_amounts_non_negative
        CHECK (subtotal >= 0 AND discount >= 0 AND total >= 0 AND previous_balance_amount >= 0) NOT VALID,
    ADD CONSTRAINT ck_orders_discount_percent_range
        CHECK (order_discount_percent >= 0 AND order_discount_percent <= 100) NOT VALID;

ALTER TABLE orders.orders VALIDATE CONSTRAINT ck_orders_amounts_non_negative;
ALTER TABLE orders.orders VALIDATE CONSTRAINT ck_orders_discount_percent_range;

ALTER TABLE orders.order_items
    ADD CONSTRAINT ck_order_items_positive_quantity_step
        CHECK (quantity > 0 AND mod(quantity * 2, 1) = 0) NOT VALID,
    ADD CONSTRAINT ck_order_items_amounts_non_negative
        CHECK (unit_price >= 0 AND line_total >= 0) NOT VALID,
    ADD CONSTRAINT ck_order_items_discount_percent_range
        CHECK (line_discount_percent >= 0 AND line_discount_percent <= 100) NOT VALID;

ALTER TABLE orders.order_items VALIDATE CONSTRAINT ck_order_items_positive_quantity_step;
ALTER TABLE orders.order_items VALIDATE CONSTRAINT ck_order_items_amounts_non_negative;
ALTER TABLE orders.order_items VALIDATE CONSTRAINT ck_order_items_discount_percent_range;

ALTER TABLE sale.sales
    ADD CONSTRAINT ck_sales_amounts_non_negative
        CHECK (total >= 0 AND paid >= 0) NOT VALID,
    ADD CONSTRAINT ck_sales_discount_percent_range
        CHECK (order_discount_percent >= 0 AND order_discount_percent <= 100) NOT VALID;

ALTER TABLE sale.sales VALIDATE CONSTRAINT ck_sales_amounts_non_negative;
ALTER TABLE sale.sales VALIDATE CONSTRAINT ck_sales_discount_percent_range;

ALTER TABLE sale.sale_items
    ADD CONSTRAINT ck_sale_items_positive_quantity_step
        CHECK (quantity > 0 AND mod(quantity * 2, 1) = 0) NOT VALID,
    ADD CONSTRAINT ck_sale_items_amounts_non_negative
        CHECK (unit_price >= 0 AND line_total >= 0) NOT VALID,
    ADD CONSTRAINT ck_sale_items_discount_percent_range
        CHECK (line_discount_percent >= 0 AND line_discount_percent <= 100) NOT VALID;

ALTER TABLE sale.sale_items VALIDATE CONSTRAINT ck_sale_items_positive_quantity_step;
ALTER TABLE sale.sale_items VALIDATE CONSTRAINT ck_sale_items_amounts_non_negative;
ALTER TABLE sale.sale_items VALIDATE CONSTRAINT ck_sale_items_discount_percent_range;

ALTER TABLE payment.payments
    ADD CONSTRAINT ck_payments_positive_amount
        CHECK (amount > 0) NOT VALID,
    ADD CONSTRAINT ck_payments_method
        CHECK (method IN ('CASH', 'BANK_TRANSFER')) NOT VALID;

ALTER TABLE payment.payments VALIDATE CONSTRAINT ck_payments_positive_amount;
ALTER TABLE payment.payments VALIDATE CONSTRAINT ck_payments_method;

ALTER TABLE sale.commission_settlements
    ALTER COLUMN amount TYPE NUMERIC(19,4) USING round(amount, 4);

ALTER TABLE sale.commission_entries
    ALTER COLUMN amount TYPE NUMERIC(19,4) USING round(amount, 4);

ALTER TABLE orders.orders
    ADD CONSTRAINT fk_orders_created_by_user
        FOREIGN KEY (created_by_user_id) REFERENCES identity.users (id) NOT VALID;
ALTER TABLE orders.orders VALIDATE CONSTRAINT fk_orders_created_by_user;

ALTER TABLE orders.order_items
    ADD CONSTRAINT fk_order_items_price_list
        FOREIGN KEY (price_list_id) REFERENCES catalog.price_lists (id) NOT VALID;
ALTER TABLE orders.order_items VALIDATE CONSTRAINT fk_order_items_price_list;

ALTER TABLE sale.sale_items
    ADD CONSTRAINT fk_sale_items_price_list
        FOREIGN KEY (price_list_id) REFERENCES catalog.price_lists (id) NOT VALID;
ALTER TABLE sale.sale_items VALIDATE CONSTRAINT fk_sale_items_price_list;

ALTER TABLE catalog.price_list_follows
    ADD CONSTRAINT fk_price_list_follows_reviewed_by
        FOREIGN KEY (reviewed_by) REFERENCES identity.users (id) NOT VALID;
ALTER TABLE catalog.price_list_follows VALIDATE CONSTRAINT fk_price_list_follows_reviewed_by;

CREATE UNIQUE INDEX ux_zones_name_lower
    ON customer.zones (LOWER(name));

ALTER TABLE customer.customers
    ADD COLUMN zone_id UUID REFERENCES customer.zones (id) ON DELETE SET NULL;

UPDATE customer.customers c
SET zone_id = z.id
FROM customer.zones z
WHERE c.zone IS NOT NULL
  AND LOWER(btrim(c.zone)) = LOWER(z.name);

CREATE INDEX ix_customers_zone_id
    ON customer.customers (zone_id)
    WHERE zone_id IS NOT NULL;

CREATE INDEX ix_customers_zone_lower
    ON customer.customers (LOWER(zone))
    WHERE zone IS NOT NULL;
