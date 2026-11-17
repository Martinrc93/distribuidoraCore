CREATE INDEX ix_active_sales_report_period ON sale.sales (created_at, id)
    WHERE status IN ('CONFIRMED', 'DELIVERED');

CREATE INDEX ix_sale_items_sale_product ON sale.sale_items (sale_id, product_id);

INSERT INTO identity.permissions (id, code, description)
VALUES ('20000000-0000-0000-0000-000000000059', 'REPORTS_VIEW', 'Consultar reportes comerciales dentro del alcance del rol')
ON CONFLICT (code) DO NOTHING;

WITH grants AS (
    INSERT INTO identity.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM identity.roles r CROSS JOIN identity.permissions p
    WHERE r.code = 'ADMIN' AND p.code = 'REPORTS_VIEW'
    ON CONFLICT DO NOTHING RETURNING role_id
), affected_users AS (
    SELECT DISTINCT ur.user_id FROM identity.user_roles ur JOIN grants g ON g.role_id = ur.role_id
), revoked AS (
    UPDATE identity.refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM affected_users) AND revoked_at IS NULL
)
UPDATE identity.users SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_id FROM affected_users);
