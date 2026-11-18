CREATE TABLE sale.commission_settlements (
    id UUID PRIMARY KEY,
    seller_id UUID NOT NULL REFERENCES seller.seller_profiles (id),
    amount NUMERIC NOT NULL CHECK (amount > 0),
    paid_at TIMESTAMPTZ NOT NULL,
    paid_by UUID NOT NULL REFERENCES identity.users (id),
    UNIQUE (id, seller_id)
);

CREATE TABLE sale.commission_entries (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id UUID NOT NULL REFERENCES seller.seller_profiles (id),
    sale_item_id UUID NOT NULL REFERENCES sale.sale_items (id),
    return_id UUID REFERENCES sale.returns (id),
    plan_id UUID NOT NULL REFERENCES app.commission_plans (id),
    plan_version BIGINT NOT NULL,
    rule_kind VARCHAR(7) NOT NULL CHECK (rule_kind IN ('FIXED', 'PERCENT')),
    rule_value NUMERIC(19,4) NOT NULL,
    unit_cost NUMERIC(19,4) NOT NULL,
    line_revenue NUMERIC(19,4) NOT NULL,
    amount NUMERIC NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    settlement_id UUID,
    FOREIGN KEY (settlement_id, seller_id) REFERENCES sale.commission_settlements (id, seller_id),
    UNIQUE NULLS NOT DISTINCT (sale_item_id, return_id),
    CHECK ((return_id IS NULL AND amount > 0) OR (return_id IS NOT NULL AND amount < 0))
);

CREATE INDEX ix_commission_entries_seller_period ON sale.commission_entries (seller_id, occurred_at DESC, id DESC);
CREATE INDEX ix_commission_entries_pending ON sale.commission_entries (seller_id, id) WHERE settlement_id IS NULL;
