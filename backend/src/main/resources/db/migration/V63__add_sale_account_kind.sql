ALTER TABLE sale.sales
    ADD COLUMN account_kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR';

UPDATE sale.sales s
SET account_kind = 'RESELLER'
FROM customer.customers c
WHERE c.id = s.account_customer_id
  AND (
      c.kind = 'RESELLER'
      OR s.account_customer_id <> s.customer_id
      OR EXISTS (
          SELECT 1
          FROM sale.sale_items si
          JOIN sale.commission_entries ce ON ce.sale_item_id = si.id
          WHERE si.sale_id = s.id AND ce.reseller_id IS NOT NULL
      )
  );

ALTER TABLE sale.sales
    ADD CONSTRAINT ck_sales_account_kind CHECK (account_kind IN ('REGULAR', 'RESELLER'));
