INSERT INTO identity.permissions (id, code, description)
VALUES
    ('20000000-0000-0000-0000-000000000043', 'ORDER_EDIT', 'Editar pedidos confirmados'),
    ('20000000-0000-0000-0000-000000000044', 'ORDER_CANCEL', 'Cancelar pedidos sin pagos'),
    ('20000000-0000-0000-0000-000000000045', 'ORDER_REACTIVATE', 'Reactivar pedidos cancelados'),
    ('20000000-0000-0000-0000-000000000046', 'SALE_RETURN', 'Registrar devoluciones de ventas'),
    ('20000000-0000-0000-0000-000000000047', 'ORDER_PRICE_OVERRIDE', 'Modificar precios manuales de pedidos'),
    ('20000000-0000-0000-0000-000000000048', 'ORDER_DISCOUNT', 'Modificar descuentos de pedidos'),
    ('20000000-0000-0000-0000-000000000049', 'ORDER_DOCUMENT_EXPORT', 'Imprimir y descargar comprobantes de pedidos'),
    ('20000000-0000-0000-0000-000000000050', 'ORDER_NOTIFY', 'Enviar comprobantes de pedidos')
ON CONFLICT (code) DO NOTHING;

-- Preserve existing document/notification access when upgrading ORDER_CREATE roles.
-- New roles select these permissions explicitly after this migration.
WITH grants AS (
    INSERT INTO identity.role_permissions (role_id, permission_id)
    SELECT r.id, p.id FROM identity.roles r CROSS JOIN identity.permissions p
    WHERE (r.code = 'ADMIN' AND p.code IN (
        'ORDER_EDIT', 'ORDER_CANCEL', 'ORDER_REACTIVATE', 'SALE_RETURN',
        'ORDER_PRICE_OVERRIDE', 'ORDER_DISCOUNT', 'ORDER_DOCUMENT_EXPORT', 'ORDER_NOTIFY'
    )) OR (p.code IN ('ORDER_DOCUMENT_EXPORT', 'ORDER_NOTIFY') AND EXISTS (
        SELECT 1 FROM identity.role_permissions rp JOIN identity.permissions old ON old.id = rp.permission_id
        WHERE rp.role_id = r.id AND old.code = 'ORDER_CREATE'
    ))
    ON CONFLICT DO NOTHING
    RETURNING role_id
), affected_users AS (
    SELECT DISTINCT ur.user_id FROM identity.user_roles ur JOIN grants g ON g.role_id = ur.role_id
), revoked AS (
    UPDATE identity.refresh_tokens SET revoked_at = CURRENT_TIMESTAMP
    WHERE user_id IN (SELECT user_id FROM affected_users) AND revoked_at IS NULL
)
UPDATE identity.users SET version = version + 1, updated_at = CURRENT_TIMESTAMP
WHERE id IN (SELECT user_id FROM affected_users);
