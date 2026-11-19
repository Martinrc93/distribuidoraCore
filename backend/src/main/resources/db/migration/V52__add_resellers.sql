-- Resellers are customers whose account owes the orders of their associated customers.
-- A customer has at most one reseller, a reseller has none, and only resellers can be referenced.
ALTER TABLE customer.customers
    ADD COLUMN kind VARCHAR(20) NOT NULL DEFAULT 'REGULAR',
    ADD COLUMN reseller_id UUID,
    ADD COLUMN reseller_kind VARCHAR(20),
    ADD CONSTRAINT ck_customer_kind CHECK (kind IN ('REGULAR', 'RESELLER')),
    ADD CONSTRAINT ux_customers_id_kind UNIQUE (id, kind),
    -- Written without NULL results: a CHECK that evaluates to NULL would accept the row.
    ADD CONSTRAINT ck_customer_reseller_kind CHECK (
        (reseller_id IS NULL) = (reseller_kind IS NULL)
        AND (reseller_kind IS NULL OR reseller_kind = 'RESELLER')),
    ADD CONSTRAINT ck_customer_reseller_not_self CHECK (reseller_id <> id),
    ADD CONSTRAINT ck_reseller_without_reseller CHECK (kind <> 'RESELLER' OR reseller_id IS NULL);

-- The reseller must keep its kind while customers reference it.
ALTER TABLE customer.customers
    ADD CONSTRAINT fk_customer_reseller FOREIGN KEY (reseller_id, reseller_kind)
        REFERENCES customer.customers (id, kind);

CREATE INDEX ix_customers_reseller ON customer.customers (reseller_id, business_name, id)
    WHERE reseller_id IS NOT NULL;

INSERT INTO identity.permissions (id, code, description)
VALUES ('20000000-0000-0000-0000-000000000060', 'RESELLER_MANAGE', 'Marcar revendedores y asignarles clientes')
ON CONFLICT (code) DO NOTHING;

WITH grants AS (
    INSERT INTO identity.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM identity.roles r CROSS JOIN identity.permissions p
    WHERE r.code = 'ADMIN' AND p.code = 'RESELLER_MANAGE'
    ON CONFLICT DO NOTHING RETURNING role_id
), affected_users AS (
    SELECT DISTINCT ur.user_id FROM identity.user_roles ur JOIN grants g ON g.role_id = ur.role_id
), revoked AS (
    UPDATE identity.refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM affected_users) AND revoked_at IS NULL
)
UPDATE identity.users SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_id FROM affected_users);
