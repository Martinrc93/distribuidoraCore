CREATE INDEX ix_active_sales_customer_report_period ON sale.sales (customer_id, created_at DESC, id DESC)
    WHERE status IN ('CONFIRMED', 'DELIVERED');
