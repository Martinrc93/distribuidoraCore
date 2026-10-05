CREATE TABLE customer.zones (
    id UUID PRIMARY KEY,
    name VARCHAR(120) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

UPDATE customer.customers
SET zone = btrim(zone)
WHERE zone IS NOT NULL AND zone <> btrim(zone);

INSERT INTO customer.zones (id, name, created_at, updated_at)
SELECT gen_random_uuid(), btrim(zone), current_timestamp, current_timestamp
FROM customer.customers
WHERE zone IS NOT NULL AND btrim(zone) <> ''
GROUP BY btrim(zone)
ON CONFLICT (name) DO NOTHING;
