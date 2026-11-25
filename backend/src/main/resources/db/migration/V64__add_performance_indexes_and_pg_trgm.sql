CREATE EXTENSION IF NOT EXISTS pg_trgm;

-- Payment and ledger hot paths (sales detail, FIFO allocations, dashboard)
CREATE INDEX IF NOT EXISTS ix_payments_sale_created
    ON payment.payments (sale_id, created_at, id);
CREATE INDEX IF NOT EXISTS ix_payments_created_at
    ON payment.payments (created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_payments_customer_created
    ON payment.payments (customer_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_account_ledger_sale_id
    ON customer.account_ledger (sale_id);

-- Order and inventory hot paths
CREATE INDEX IF NOT EXISTS ix_order_items_order_id
    ON orders.order_items (order_id);
CREATE INDEX IF NOT EXISTS ix_order_items_product_id
    ON orders.order_items (product_id);
CREATE INDEX IF NOT EXISTS ix_stock_movements_reference_id
    ON inventory.stock_movements (reference_id)
    WHERE reference_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_orders_customer_created
    ON orders.orders (customer_id, created_at DESC, id DESC);
CREATE INDEX IF NOT EXISTS ix_orders_seller_created
    ON orders.orders (seller_id, created_at DESC);
CREATE INDEX IF NOT EXISTS ix_orders_status_created
    ON orders.orders (status, created_at, id);

-- Open account debt & commercial analytics
CREATE INDEX IF NOT EXISTS ix_sales_account_open_unpaid
    ON sale.sales (account_customer_id, created_at, id)
    WHERE status IN ('CONFIRMED', 'DELIVERED') AND paid < total;
CREATE INDEX IF NOT EXISTS ix_sale_items_product_id
    ON sale.sale_items (product_id);
CREATE INDEX IF NOT EXISTS ix_return_items_sale_id
    ON sale.return_items (sale_id);
CREATE INDEX IF NOT EXISTS ix_return_items_product_id
    ON sale.return_items (product_id);
CREATE INDEX IF NOT EXISTS ix_supplier_order_items_product_id
    ON purchasing.supplier_order_items (product_id);

-- Customers, catalog and pricing hot paths
CREATE INDEX IF NOT EXISTS ix_customers_seller_id
    ON customer.customers (seller_id);
CREATE INDEX IF NOT EXISTS ix_customers_positive_balance
    ON customer.customers (balance DESC, id)
    WHERE balance > 0;
CREATE INDEX IF NOT EXISTS ix_products_status
    ON catalog.products (status);
CREATE INDEX IF NOT EXISTS ix_product_price_history_covering
    ON catalog.product_price_history (price_list_id, product_id, effective_on DESC, created_at DESC, id DESC)
    INCLUDE (price);

-- Outbox, notification retention & audit lookups
CREATE INDEX IF NOT EXISTS ix_outbox_events_created_at
    ON notification.outbox_events (created_at);
CREATE INDEX IF NOT EXISTS ix_delivery_requests_created_at
    ON notification.delivery_requests (created_at);
CREATE INDEX IF NOT EXISTS ix_delivery_requests_order_id
    ON notification.delivery_requests (order_id);
CREATE INDEX IF NOT EXISTS ix_audit_events_operation_resource
    ON audit.audit_events (operation, resource_id);

-- Commissions (partial seller/settlement indexes)
CREATE INDEX IF NOT EXISTS ix_commission_entries_seller_period_active
    ON sale.commission_entries (seller_id, occurred_at DESC, id DESC)
    WHERE seller_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_commission_entries_seller_pending_active
    ON sale.commission_entries (seller_id, id)
    WHERE settlement_id IS NULL AND seller_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_commission_entries_settlement
    ON sale.commission_entries (settlement_id)
    WHERE settlement_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_commission_settlements_seller
    ON sale.commission_settlements (seller_id)
    WHERE seller_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS ix_commission_settlements_reseller
    ON sale.commission_settlements (reseller_id)
    WHERE reseller_id IS NOT NULL;

-- Trigram indexes for substring search (LIKE '%...%')
CREATE INDEX IF NOT EXISTS ix_customers_business_name_trgm
    ON customer.customers USING GIN (LOWER(business_name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_products_name_trgm
    ON catalog.products USING GIN (LOWER(name) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_orders_order_number_trgm
    ON orders.orders USING GIN (LOWER(order_number) gin_trgm_ops);
CREATE INDEX IF NOT EXISTS ix_sales_sale_number_trgm
    ON sale.sales USING GIN (LOWER(sale_number) gin_trgm_ops);
