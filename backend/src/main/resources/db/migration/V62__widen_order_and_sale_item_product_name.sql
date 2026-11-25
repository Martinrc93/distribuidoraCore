ALTER TABLE orders.order_items
    ALTER COLUMN product_name TYPE VARCHAR(301);

ALTER TABLE sale.sale_items
    ALTER COLUMN product_name TYPE VARCHAR(301);
