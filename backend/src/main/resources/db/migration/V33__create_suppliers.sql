CREATE SCHEMA IF NOT EXISTS supplier;

CREATE TABLE supplier.suppliers (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (length(trim(name)) > 0),
    phone VARCHAR(80),
    email VARCHAR(200),
    address VARCHAR(500),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE INDEX idx_suppliers_name_id ON supplier.suppliers (name, id);
