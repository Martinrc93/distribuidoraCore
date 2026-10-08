CREATE TABLE catalog.product_suppliers (
    product_id UUID NOT NULL REFERENCES catalog.products(id),
    supplier_id UUID NOT NULL REFERENCES supplier.suppliers(id),
    PRIMARY KEY (product_id, supplier_id)
);
CREATE INDEX ix_product_suppliers_supplier ON catalog.product_suppliers(supplier_id, product_id);

CREATE SCHEMA IF NOT EXISTS purchasing;
CREATE SEQUENCE purchasing.supplier_order_number_seq;

CREATE TABLE purchasing.supplier_orders (
    id UUID PRIMARY KEY,
    order_number VARCHAR(30) NOT NULL UNIQUE,
    supplier_id UUID NOT NULL REFERENCES supplier.suppliers(id),
    supplier_name VARCHAR(200) NOT NULL,
    order_date DATE NOT NULL,
    total NUMERIC(19,4) NOT NULL CHECK (total >= 0),
    idempotency_key VARCHAR(100) NOT NULL UNIQUE,
    request_fingerprint VARCHAR(64) NOT NULL,
    created_by UUID REFERENCES identity.users(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL
);
CREATE INDEX ix_supplier_orders_history ON purchasing.supplier_orders(supplier_id, order_date DESC, created_at DESC, id DESC);
CREATE INDEX ix_supplier_orders_date ON purchasing.supplier_orders(order_date DESC, created_at DESC, id DESC);

CREATE TABLE purchasing.supplier_order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES purchasing.supplier_orders(id),
    product_id UUID NOT NULL REFERENCES catalog.products(id),
    product_name VARCHAR(301) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL CHECK (quantity > 0),
    unit_cost NUMERIC(19,4) NOT NULL CHECK (unit_cost >= 0),
    line_total NUMERIC(19,4) NOT NULL CHECK (line_total >= 0),
    UNIQUE(order_id, product_id)
);
