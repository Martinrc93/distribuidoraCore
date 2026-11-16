ALTER TABLE orders.orders
    ADD COLUMN fulfillment_status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN prepared_at TIMESTAMPTZ,
    ADD COLUMN prepared_by_user_id UUID REFERENCES identity.users(id),
    ADD COLUMN legacy_fulfillment BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN reviewed_at TIMESTAMPTZ,
    ADD COLUMN reviewed_by_user_id UUID REFERENCES identity.users(id);

-- Before V42 confirmation already deducted stock. Do not deduct it again, and do
-- not invent a preparation date or operator. Cancelled orders already reversed it.
UPDATE orders.orders
SET fulfillment_status = 'PREPARED', legacy_fulfillment = TRUE
WHERE status <> 'CANCELLED';

ALTER TABLE orders.orders
    ADD CONSTRAINT ck_order_fulfillment CHECK (
        (fulfillment_status = 'PENDING' AND prepared_at IS NULL AND prepared_by_user_id IS NULL AND NOT legacy_fulfillment)
        OR (fulfillment_status = 'PREPARED' AND (
            (legacy_fulfillment AND prepared_at IS NULL AND prepared_by_user_id IS NULL)
            OR (NOT legacy_fulfillment AND prepared_at IS NOT NULL AND prepared_by_user_id IS NOT NULL)))),
    ADD CONSTRAINT ck_order_review CHECK ((reviewed_at IS NULL) = (reviewed_by_user_id IS NULL));

CREATE INDEX ix_orders_fulfillment ON orders.orders (fulfillment_status, created_at DESC) WHERE status = 'CONFIRMED';
CREATE INDEX ix_orders_pending_review ON orders.orders (created_at DESC) WHERE created_by_admin = FALSE AND reviewed_at IS NULL;

INSERT INTO identity.permissions (id, code, description) VALUES
    ('20000000-0000-0000-0000-000000000008', 'ORDER_PREPARE', 'Preparar pedidos y descontar su stock'),
    ('20000000-0000-0000-0000-000000000009', 'ORDER_REVIEW', 'Confirmar la revisión administrativa de pedidos de vendedores')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM identity.roles r CROSS JOIN identity.permissions p
WHERE (r.code IN ('ADMIN', 'SELLER') AND p.code = 'ORDER_PREPARE')
   OR (r.code = 'ADMIN' AND p.code = 'ORDER_REVIEW')
ON CONFLICT (role_id, permission_id) DO NOTHING;
