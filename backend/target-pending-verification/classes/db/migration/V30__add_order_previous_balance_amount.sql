ALTER TABLE orders.orders
    ADD COLUMN previous_balance_amount NUMERIC(19,4) NOT NULL DEFAULT 0,
    ADD CONSTRAINT order_previous_balance_amount_nonnegative CHECK (previous_balance_amount >= 0);
