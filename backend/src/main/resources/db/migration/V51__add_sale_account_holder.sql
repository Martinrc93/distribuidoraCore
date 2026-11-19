-- Each sale stores the customer account that owes it, separately from the customer
-- that receives the order. Until resellers exist, the account is always the recipient.
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM customer.account_ledger l JOIN sale.sales s ON s.id = l.sale_id
        WHERE l.customer_id <> s.customer_id
    ) THEN
        RAISE EXCEPTION 'Account ledger entries must belong to the customer of their sale before adding sale account holders';
    END IF;
    IF EXISTS (
        SELECT 1 FROM payment.payments p JOIN sale.sales s ON s.id = p.sale_id
        WHERE p.customer_id <> s.customer_id
    ) THEN
        RAISE EXCEPTION 'Payments must belong to the customer of their sale before adding sale account holders';
    END IF;
END $$;

ALTER TABLE sale.sales ADD COLUMN account_customer_id UUID;
UPDATE sale.sales SET account_customer_id = customer_id;
ALTER TABLE sale.sales
    ALTER COLUMN account_customer_id SET NOT NULL,
    ADD CONSTRAINT fk_sales_account_customer
        FOREIGN KEY (account_customer_id) REFERENCES customer.customers (id),
    ADD CONSTRAINT ux_sales_id_account UNIQUE (id, account_customer_id);

-- Ledger entries and payments always belong to the account that owes their sale.
ALTER TABLE customer.account_ledger
    ADD CONSTRAINT fk_account_ledger_sale_account
        FOREIGN KEY (sale_id, customer_id) REFERENCES sale.sales (id, account_customer_id);
ALTER TABLE payment.payments
    ADD CONSTRAINT fk_payments_sale_account
        FOREIGN KEY (sale_id, customer_id) REFERENCES sale.sales (id, account_customer_id);

-- FIFO, balances and statements read the open sales of an account in chronological order.
CREATE INDEX ix_sales_account_open ON sale.sales (account_customer_id, created_at, id)
    WHERE status IN ('CONFIRMED', 'DELIVERED');
