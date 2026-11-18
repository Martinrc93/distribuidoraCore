CREATE TABLE app.commission_plans (
    id UUID PRIMARY KEY,
    name VARCHAR(100) NOT NULL CHECK (length(trim(name)) > 0),
    version BIGINT NOT NULL DEFAULT 1 CHECK (version >= 1)
);

CREATE UNIQUE INDEX ux_commission_plans_name ON app.commission_plans (lower(name));

CREATE TABLE app.commission_rules (
    plan_id UUID NOT NULL REFERENCES app.commission_plans (id) ON DELETE CASCADE,
    category_id UUID REFERENCES catalog.categories (id),
    brand_id UUID REFERENCES catalog.brands (id),
    product_id UUID REFERENCES catalog.products (id),
    kind VARCHAR(7) NOT NULL CHECK (kind IN ('FIXED', 'PERCENT')),
    value NUMERIC(19,4) NOT NULL CHECK (value >= 0 AND (kind <> 'PERCENT' OR value <= 100)),
    CHECK (num_nonnulls(category_id, brand_id, product_id) <= 1),
    UNIQUE NULLS NOT DISTINCT (plan_id, category_id, brand_id, product_id)
);

CREATE TABLE app.seller_commission_plans (
    seller_id UUID PRIMARY KEY REFERENCES seller.seller_profiles (id),
    plan_id UUID NOT NULL REFERENCES app.commission_plans (id)
);

CREATE INDEX ix_seller_commission_plans_plan ON app.seller_commission_plans (plan_id);
