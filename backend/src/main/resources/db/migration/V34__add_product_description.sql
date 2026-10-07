ALTER TABLE catalog.products ADD COLUMN description VARCHAR(200);
ALTER TABLE catalog.products ALTER COLUMN name SET DATA TYPE VARCHAR(301);

UPDATE catalog.products SET description = trim(name);

-- Separate a leading brand from the legacy name without discarding the description.
UPDATE catalog.products p
SET description = trim(substring(p.description FROM
    (SELECT char_length(trim(b.name)) + 2 FROM catalog.brands b WHERE b.id = p.brand_id)))
WHERE EXISTS (
    SELECT 1 FROM catalog.brands b
    WHERE b.id = p.brand_id
      AND lower(substring(p.description FROM 1 FOR char_length(trim(b.name)) + 1)) = lower(trim(b.name)) || ' '
      AND char_length(p.description) > char_length(trim(b.name)) + 1
);

UPDATE catalog.products p
SET name = (SELECT trim(b.name) || ' ' || p.description FROM catalog.brands b WHERE b.id = p.brand_id)
WHERE p.brand_id IS NOT NULL;
