INSERT INTO identity.permissions (id, code, description) VALUES
    ('20000000-0000-0000-0000-000000000051', 'CUSTOMER_VIEW', 'Consultar ficha e historial de clientes'),
    ('20000000-0000-0000-0000-000000000052', 'CUSTOMER_CREATE', 'Crear clientes'),
    ('20000000-0000-0000-0000-000000000053', 'CUSTOMER_EDIT', 'Editar datos de clientes'),
    ('20000000-0000-0000-0000-000000000054', 'CUSTOMER_STATUS_CHANGE', 'Activar y desactivar clientes'),
    ('20000000-0000-0000-0000-000000000055', 'CUSTOMER_PRICE_LIST_CHANGE', 'Asignar la lista predeterminada del cliente'),
    ('20000000-0000-0000-0000-000000000056', 'ACCOUNT_SETTLE', 'Registrar pagos combinados y saldar cuentas asignadas'),
    ('20000000-0000-0000-0000-000000000057', 'CUSTOMER_STATEMENT_EXPORT', 'Imprimir y descargar estados de deuda')
ON CONFLICT (code) DO NOTHING;

WITH grants AS (
    INSERT INTO identity.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM identity.roles r CROSS JOIN identity.permissions p
    WHERE (r.code = 'ADMIN' AND p.code IN ('CUSTOMER_VIEW', 'CUSTOMER_CREATE', 'CUSTOMER_EDIT',
        'CUSTOMER_STATUS_CHANGE', 'CUSTOMER_PRICE_LIST_CHANGE', 'ACCOUNT_SETTLE', 'CUSTOMER_STATEMENT_EXPORT'))
       OR (p.code = 'CUSTOMER_STATEMENT_EXPORT' AND EXISTS (
            SELECT 1 FROM identity.role_permissions rp JOIN identity.permissions old ON old.id = rp.permission_id
            WHERE rp.role_id = r.id AND old.code = 'SALE_PAYMENT'
       ))
    ON CONFLICT DO NOTHING RETURNING role_id
), affected_users AS (
    SELECT DISTINCT ur.user_id FROM identity.user_roles ur JOIN grants g ON g.role_id = ur.role_id
), revoked AS (
    UPDATE identity.refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM affected_users) AND revoked_at IS NULL
)
UPDATE identity.users SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_id FROM affected_users);
