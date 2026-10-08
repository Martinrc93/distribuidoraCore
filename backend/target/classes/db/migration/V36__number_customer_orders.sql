-- Assign stable customer numbers and preserve previous order numbers as aliases.
CREATE SEQUENCE customer.customer_number_seq MINVALUE 1 MAXVALUE 9999 NO CYCLE;
ALTER TABLE customer.customers ADD COLUMN customer_number INTEGER;
ALTER TABLE customer.customers ADD COLUMN last_order_number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE orders.orders ADD COLUMN legacy_order_number VARCHAR(30) UNIQUE;

DO $$
BEGIN
    IF (SELECT count(*) FROM customer.customers) > 9999
       OR EXISTS (SELECT 1 FROM orders.orders GROUP BY customer_id HAVING count(*) > 99999) THEN
        RAISE EXCEPTION 'Existing data exceeds the four-digit customer or five-digit order numbering capacity';
    END IF;
END $$;

WITH numbered AS (
    SELECT id, row_number() OVER (ORDER BY created_at, id)::integer AS number
    FROM customer.customers
)
UPDATE customer.customers c SET customer_number = numbered.number FROM numbered WHERE c.id = numbered.id;

SELECT setval('customer.customer_number_seq',
    greatest(coalesce((SELECT max(customer_number) FROM customer.customers), 0), 1),
    EXISTS (SELECT 1 FROM customer.customers));

ALTER TABLE customer.customers ALTER COLUMN customer_number SET DEFAULT nextval('customer.customer_number_seq');
ALTER TABLE customer.customers ALTER COLUMN customer_number SET NOT NULL;
ALTER TABLE customer.customers ADD CONSTRAINT ux_customer_number UNIQUE(customer_number);
ALTER TABLE customer.customers ADD CONSTRAINT ck_customer_number CHECK(customer_number BETWEEN 1 AND 9999);
ALTER TABLE customer.customers ADD CONSTRAINT ck_customer_last_order_number CHECK(last_order_number BETWEEN 0 AND 99999);

UPDATE orders.orders SET legacy_order_number = order_number;
ALTER TABLE orders.orders DROP CONSTRAINT orders_order_number_key;
WITH numbered AS (
    SELECT o.id, c.customer_number,
        row_number() OVER (PARTITION BY o.customer_id ORDER BY o.created_at, o.id)::integer AS number
    FROM orders.orders o JOIN customer.customers c ON c.id = o.customer_id
)
UPDATE orders.orders o
SET order_number = lpad(numbered.customer_number::text, 4, '0') || lpad(numbered.number::text, 5, '0')
FROM numbered WHERE o.id = numbered.id;
ALTER TABLE orders.orders ADD CONSTRAINT orders_order_number_key UNIQUE(order_number);

UPDATE customer.customers c SET last_order_number = (SELECT count(*) FROM orders.orders o WHERE o.customer_id = c.id);

-- A transactional row update serializes concurrent requests for the same customer.
CREATE FUNCTION orders.next_customer_order_number(customer_id UUID) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
    number TEXT;
BEGIN
    UPDATE customer.customers c SET last_order_number = last_order_number + 1
    WHERE c.id = customer_id AND last_order_number < 99999
    RETURNING lpad(c.customer_number::text, 4, '0') || lpad(c.last_order_number::text, 5, '0') INTO number;
    IF number IS NULL THEN
        RAISE EXCEPTION 'Customer order numbering limit reached or customer not found' USING ERRCODE = '22003';
    END IF;
    RETURN number;
END $$;
