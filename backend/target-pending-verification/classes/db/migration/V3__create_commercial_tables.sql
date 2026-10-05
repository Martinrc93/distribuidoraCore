CREATE SCHEMA IF NOT EXISTS demo;

CREATE TABLE seller.seller_profiles (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL UNIQUE REFERENCES identity.users (id),
    display_name VARCHAR(160) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_seller_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE TABLE customer.customers (
    id UUID PRIMARY KEY,
    business_name VARCHAR(200) NOT NULL,
    tax_id VARCHAR(40) NOT NULL UNIQUE,
    seller_id UUID REFERENCES seller.seller_profiles (id),
    balance NUMERIC(19,4) NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_customer_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX ix_customers_business_name ON customer.customers (LOWER(business_name));

CREATE TABLE catalog.products (
    id UUID PRIMARY KEY,
    sku VARCHAR(40) NOT NULL UNIQUE,
    name VARCHAR(200) NOT NULL,
    category VARCHAR(100) NOT NULL,
    presentation VARCHAR(120) NOT NULL,
    cost NUMERIC(19,4) NOT NULL,
    price NUMERIC(19,4) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_product_status CHECK (status IN ('ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_product_amounts CHECK (cost >= 0 AND price >= 0)
);

CREATE INDEX ix_products_name ON catalog.products (LOWER(name));

CREATE TABLE inventory.inventory_balances (
    product_id UUID PRIMARY KEY REFERENCES catalog.products (id),
    quantity NUMERIC(19,4) NOT NULL DEFAULT 0,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE inventory.stock_movements (
    id UUID PRIMARY KEY,
    product_id UUID NOT NULL REFERENCES catalog.products (id),
    movement_type VARCHAR(30) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    reference_type VARCHAR(50),
    reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE orders.orders (
    id UUID PRIMARY KEY,
    order_number VARCHAR(30) NOT NULL UNIQUE,
    customer_id UUID NOT NULL REFERENCES customer.customers (id),
    seller_id UUID REFERENCES seller.seller_profiles (id),
    status VARCHAR(20) NOT NULL,
    subtotal NUMERIC(19,4) NOT NULL,
    discount NUMERIC(19,4) NOT NULL DEFAULT 0,
    total NUMERIC(19,4) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_order_status CHECK (status IN ('CONFIRMED', 'DELIVERED', 'CANCELLED'))
);

CREATE INDEX ix_orders_created_at ON orders.orders (created_at DESC);

CREATE TABLE orders.order_items (
    id UUID PRIMARY KEY,
    order_id UUID NOT NULL REFERENCES orders.orders (id),
    product_id UUID NOT NULL REFERENCES catalog.products (id),
    product_name VARCHAR(200) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    line_total NUMERIC(19,4) NOT NULL
);

CREATE TABLE sale.sales (
    id UUID PRIMARY KEY,
    sale_number VARCHAR(30) NOT NULL UNIQUE,
    order_id UUID NOT NULL UNIQUE REFERENCES orders.orders (id),
    customer_id UUID NOT NULL REFERENCES customer.customers (id),
    status VARCHAR(20) NOT NULL,
    total NUMERIC(19,4) NOT NULL,
    paid NUMERIC(19,4) NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_sale_status CHECK (status IN ('CONFIRMED', 'DELIVERED', 'CANCELLED'))
);

CREATE TABLE sale.sale_items (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES sale.sales (id),
    product_id UUID NOT NULL REFERENCES catalog.products (id),
    product_name VARCHAR(200) NOT NULL,
    quantity NUMERIC(19,4) NOT NULL,
    unit_price NUMERIC(19,4) NOT NULL,
    line_total NUMERIC(19,4) NOT NULL
);

CREATE TABLE payment.payments (
    id UUID PRIMARY KEY,
    sale_id UUID NOT NULL REFERENCES sale.sales (id),
    customer_id UUID NOT NULL REFERENCES customer.customers (id),
    amount NUMERIC(19,4) NOT NULL,
    method VARCHAR(30) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE demo.seed_runs (
    name VARCHAR(80) PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL
);
