ALTER TABLE inventory.stock_movements
    DROP CONSTRAINT ck_stock_movement_type,
    ADD CONSTRAINT ck_stock_movement_type
        CHECK (movement_type IN ('SALE', 'SALE_CANCELLATION', 'MANUAL_ENTRY', 'MANUAL_ADJUSTMENT', 'RETURN',
                                 'TRANSFER_OUT', 'TRANSFER_IN', 'PURCHASE', 'PURCHASE_ADJUSTMENT',
                                 'PURCHASE_CANCELLATION', 'SHRINKAGE')),
    ADD CONSTRAINT ck_stock_shrinkage
        CHECK (movement_type <> 'SHRINKAGE' OR (quantity < 0 AND reason ~ '[^[:space:]]'));

CREATE INDEX ix_stock_shrinkages_created_at
    ON inventory.stock_movements (created_at DESC, id DESC) WHERE movement_type = 'SHRINKAGE';

INSERT INTO identity.permissions (id, code, description)
VALUES ('20000000-0000-0000-0000-000000000058', 'STOCK_SHRINKAGE', 'Registrar mermas de stock')
ON CONFLICT (code) DO NOTHING;

WITH grants AS (
    INSERT INTO identity.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM identity.roles r CROSS JOIN identity.permissions p
    WHERE r.code = 'ADMIN' AND p.code = 'STOCK_SHRINKAGE'
    ON CONFLICT DO NOTHING RETURNING role_id
), affected_users AS (
    SELECT DISTINCT ur.user_id FROM identity.user_roles ur JOIN grants g ON g.role_id = ur.role_id
), revoked AS (
    UPDATE identity.refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM affected_users) AND revoked_at IS NULL
)
UPDATE identity.users SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_id FROM affected_users);
