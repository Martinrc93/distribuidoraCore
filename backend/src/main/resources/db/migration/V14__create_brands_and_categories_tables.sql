CREATE TABLE catalog.categories (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_category_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX ix_categories_name_lower ON catalog.categories (LOWER(name));

CREATE TABLE catalog.brands (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL,
    code VARCHAR(50) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_brand_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE UNIQUE INDEX ix_brands_name_lower ON catalog.brands (LOWER(name));

ALTER TABLE catalog.products
    ADD COLUMN IF NOT EXISTS brand_id UUID REFERENCES catalog.brands (id),
    ADD COLUMN IF NOT EXISTS category_id UUID REFERENCES catalog.categories (id);

CREATE INDEX IF NOT EXISTS ix_products_brand_id ON catalog.products (brand_id);
CREATE INDEX IF NOT EXISTS ix_products_category_id ON catalog.products (category_id);
