-- Who registered each order and whether that user acted as administrator at that moment
-- (a snapshot: later role changes do not rewrite history).
ALTER TABLE orders.orders
    ADD COLUMN created_by_user_id UUID,
    ADD COLUMN created_by_admin BOOLEAN;

-- Historical orders: take the actor of the ORDER_CONFIRM audit event when it exists. The admin flag
-- is derived from the user's current ADMIN role (best effort); orders without audit stay unknown (NULL).
UPDATE orders.orders o
SET created_by_user_id = a.actor_user_id,
    created_by_admin = EXISTS (
        SELECT 1 FROM identity.user_roles ur
        JOIN identity.roles r ON r.id = ur.role_id
        WHERE ur.user_id = a.actor_user_id AND r.code = 'ADMIN')
FROM (
    SELECT DISTINCT ON (resource_id) resource_id, actor_user_id
    FROM audit.audit_events
    WHERE operation = 'ORDER_CONFIRM' AND resource_type = 'ORDER' AND result = 'SUCCESS' AND actor_user_id IS NOT NULL
    ORDER BY resource_id, created_at
) a
WHERE a.resource_id = o.id::text
  AND EXISTS (SELECT 1 FROM identity.users u WHERE u.id = a.actor_user_id);

CREATE INDEX ix_orders_created_by_admin ON orders.orders (created_by_admin, created_at DESC);
