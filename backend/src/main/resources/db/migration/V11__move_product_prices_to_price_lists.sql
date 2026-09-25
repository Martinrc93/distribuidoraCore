INSERT INTO catalog.product_prices (price_list_id, product_id, price, created_at, updated_at)
SELECT pl.id, p.id, p.price, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM catalog.price_lists pl
JOIN catalog.products p ON TRUE
WHERE pl.status = 'ACTIVE'
  AND NOT EXISTS (
      SELECT 1 FROM catalog.product_prices pp
      WHERE pp.price_list_id = pl.id AND pp.product_id = p.id
  );

ALTER TABLE catalog.products DROP CONSTRAINT ck_product_amounts;
ALTER TABLE catalog.products DROP COLUMN price;
ALTER TABLE catalog.products
    ADD CONSTRAINT ck_product_cost CHECK (cost >= 0);
