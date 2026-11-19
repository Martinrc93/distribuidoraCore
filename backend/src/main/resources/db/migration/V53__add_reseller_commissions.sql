-- Phase 9.3: resellers can earn commissions on the sales charged to their account.

-- Only resellers can have a plan, and the composite key keeps a reseller with a plan from losing its mark.
CREATE TABLE app.reseller_commission_plans (
    customer_id UUID PRIMARY KEY,
    customer_kind VARCHAR(20) NOT NULL DEFAULT 'RESELLER' CHECK (customer_kind = 'RESELLER'),
    plan_id UUID NOT NULL REFERENCES app.commission_plans (id),
    CONSTRAINT fk_reseller_commission_plans_customer
        FOREIGN KEY (customer_id, customer_kind) REFERENCES customer.customers (id, kind)
);

CREATE INDEX ix_reseller_commission_plans_plan ON app.reseller_commission_plans (plan_id);

-- Settlements and entries belong to exactly one beneficiary: a seller or a reseller.
ALTER TABLE sale.commission_settlements
    ALTER COLUMN seller_id DROP NOT NULL,
    ADD COLUMN reseller_id UUID REFERENCES customer.customers (id),
    ADD CONSTRAINT ck_commission_settlements_beneficiary CHECK (num_nonnulls(seller_id, reseller_id) = 1),
    ADD CONSTRAINT ux_commission_settlements_id_reseller UNIQUE (id, reseller_id);

ALTER TABLE sale.commission_entries
    ALTER COLUMN seller_id DROP NOT NULL,
    ADD COLUMN reseller_id UUID REFERENCES customer.customers (id),
    ADD CONSTRAINT ck_commission_entries_beneficiary CHECK (num_nonnulls(seller_id, reseller_id) = 1);

-- The seller key already ties seller entries to their settlement; this one does it for resellers.
ALTER TABLE sale.commission_entries
    ADD CONSTRAINT fk_commission_entries_reseller_settlement
        FOREIGN KEY (settlement_id, reseller_id) REFERENCES sale.commission_settlements (id, reseller_id);

-- A sale line can earn one seller commission and one reseller commission, each once per return.
ALTER TABLE sale.commission_entries
    ADD COLUMN beneficiary VARCHAR(8) GENERATED ALWAYS AS (
        CASE WHEN reseller_id IS NULL THEN 'SELLER' ELSE 'RESELLER' END) STORED;

ALTER TABLE sale.commission_entries
    DROP CONSTRAINT commission_entries_sale_item_id_return_id_key,
    ADD CONSTRAINT ux_commission_entries_line_beneficiary
        UNIQUE NULLS NOT DISTINCT (sale_item_id, return_id, beneficiary);

CREATE INDEX ix_commission_entries_reseller_period
    ON sale.commission_entries (reseller_id, occurred_at DESC, id DESC) WHERE reseller_id IS NOT NULL;
CREATE INDEX ix_commission_entries_reseller_pending
    ON sale.commission_entries (reseller_id, id) WHERE settlement_id IS NULL AND reseller_id IS NOT NULL;
