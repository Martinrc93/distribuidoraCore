WITH default_rules(name, kind, value) AS (
    VALUES
        ('10% de la ganancia por producto', 'PERCENT', 10),
        ('$1 por unidad vendida', 'FIXED', 1)
),
new_plans AS (
    INSERT INTO app.commission_plans (id, name)
    SELECT gen_random_uuid(), name
    FROM default_rules
    ON CONFLICT (lower(name)) DO NOTHING
    RETURNING id, name
)
INSERT INTO app.commission_rules (plan_id, kind, value)
SELECT p.id, r.kind, r.value
FROM new_plans p
JOIN default_rules r ON r.name = p.name;
