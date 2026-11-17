UPDATE identity.permissions
SET description = 'Ajustar el stock global (solo Administración)'
WHERE code = 'STOCK_ADJUST';

WITH removed_grants AS (
    DELETE FROM identity.role_permissions rp
    USING identity.roles r, identity.permissions p
    WHERE rp.role_id = r.id AND rp.permission_id = p.id
      AND r.code <> 'ADMIN' AND p.code IN ('STOCK_ADJUST', 'USER_MANAGE')
    RETURNING rp.role_id
), affected_users AS (
    SELECT DISTINCT ur.user_id
    FROM identity.user_roles ur JOIN removed_grants g ON g.role_id = ur.role_id
), revoked AS (
    UPDATE identity.refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM affected_users) AND revoked_at IS NULL
)
UPDATE identity.users SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_id FROM affected_users);
