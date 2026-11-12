-- Sellers may choose another active price list for an order (requested by the client).
-- Kept as its own permission so an instance can revoke it per role without code changes.
INSERT INTO identity.permissions (id, code, description)
VALUES ('20000000-0000-0000-0000-000000000007', 'ORDER_PRICE_LIST_CHANGE', 'Cambiar la lista de precios de un pedido')
ON CONFLICT (code) DO NOTHING;

INSERT INTO identity.role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM identity.roles r
CROSS JOIN identity.permissions p
WHERE r.code IN ('ADMIN', 'SELLER') AND p.code = 'ORDER_PRICE_LIST_CHANGE'
ON CONFLICT (role_id, permission_id) DO NOTHING;
