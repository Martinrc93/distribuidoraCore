INSERT INTO identity.permissions (id, code, description)
VALUES ('20000000-0000-0000-0000-000000000006', 'SALE_PAYMENT', 'Registrar pagos de cuenta corriente')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.code = 'SELLER' AND p.code = 'SALE_PAYMENT'
ON CONFLICT (role_id, permission_id) DO NOTHING;
