CREATE SEQUENCE sale.sale_number_seq AS BIGINT NO CYCLE;

-- Continue after any existing numeric V- codes, including older demo numbers.
WITH existing AS (
    SELECT MAX(substring(sale_number from '^V-([0-9]+)$')::numeric) AS last_number
    FROM sale.sales
    WHERE sale_number ~ '^V-[0-9]+$'
)
SELECT setval('sale.sale_number_seq', greatest(1, last_number)::bigint, coalesce(last_number > 0, false))
FROM existing;

CREATE FUNCTION sale.next_sale_number() RETURNS TEXT
LANGUAGE sql VOLATILE AS $$
    WITH allocated AS (
        SELECT nextval('sale.sale_number_seq')::text AS number
    )
    SELECT 'V-' || lpad(number, greatest(8, length(number)), '0')
    FROM allocated;
$$;
