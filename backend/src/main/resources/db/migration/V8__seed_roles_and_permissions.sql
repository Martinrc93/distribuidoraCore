INSERT INTO identity.roles (id, code, description)
VALUES
    ('10000000-0000-0000-0000-000000000001', 'ADMIN', 'Administrador'),
    ('10000000-0000-0000-0000-000000000002', 'SELLER', 'Vendedor')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.permissions (id, code, description)
VALUES
    ('20000000-0000-0000-0000-000000000001', 'ADMIN_ALL', 'Acceso administrativo completo'),
    ('20000000-0000-0000-0000-000000000002', 'USER_MANAGE', 'Administrar usuarios'),
    ('20000000-0000-0000-0000-000000000003', 'ORDER_CREATE', 'Crear pedidos'),
    ('20000000-0000-0000-0000-000000000004', 'SALE_DELIVER', 'Entregar ventas')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.code = 'ADMIN' AND p.code IN ('ADMIN_ALL', 'USER_MANAGE')
ON CONFLICT (role_id, permission_id) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.code = 'SELLER' AND p.code IN ('ORDER_CREATE', 'SALE_DELIVER')
ON CONFLICT (role_id, permission_id) DO NOTHING;
