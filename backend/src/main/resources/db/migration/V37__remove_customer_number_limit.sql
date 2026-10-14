-- Preserve existing numbers and the sequence position while allowing longer customer numbers.
ALTER TABLE customer.customers DROP CONSTRAINT ck_customer_number;
ALTER TABLE customer.customers ALTER COLUMN customer_number TYPE BIGINT;
ALTER TABLE customer.customers ADD CONSTRAINT ck_customer_number CHECK (customer_number >= 1);
ALTER SEQUENCE customer.customer_number_seq NO MAXVALUE;

-- Four digits remain a minimum display width, rather than a truncation limit.
CREATE OR REPLACE FUNCTION orders.next_customer_order_number(customer_id UUID) RETURNS TEXT
LANGUAGE plpgsql AS $$
DECLARE
    number TEXT;
BEGIN
    UPDATE customer.customers c SET last_order_number = last_order_number + 1
    WHERE c.id = customer_id AND last_order_number < 99999
    RETURNING lpad(c.customer_number::text, greatest(4, length(c.customer_number::text)), '0')
        || lpad(c.last_order_number::text, 5, '0') INTO number;
    IF number IS NULL THEN
        RAISE EXCEPTION 'Customer order numbering limit reached or customer not found' USING ERRCODE = '22003';
    END IF;
    RETURN number;
END $$;
