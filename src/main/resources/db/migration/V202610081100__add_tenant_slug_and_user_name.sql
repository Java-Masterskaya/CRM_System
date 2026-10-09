ALTER TABLE tenants ADD COLUMN slug VARCHAR(100);

WITH normalized AS (
    SELECT id,
           COALESCE(NULLIF(trim(BOTH '-' FROM regexp_replace(lower(name),
               '[^a-z0-9]+', '-', 'g')), ''), 'tenant') AS base_slug
    FROM tenants
), ranked AS (
    SELECT id, base_slug,
           row_number() OVER (PARTITION BY base_slug ORDER BY id) AS duplicate_number
    FROM normalized
)
UPDATE tenants t
SET slug = CASE WHEN ranked.duplicate_number = 1 THEN ranked.base_slug
                ELSE ranked.base_slug || '-' || left(t.id::text, 8)
           END
FROM ranked
WHERE ranked.id = t.id;

CREATE UNIQUE INDEX uk_tenants_slug ON tenants (lower(slug));

ALTER TABLE users ADD COLUMN name VARCHAR(255);
ALTER TABLE users ADD COLUMN phone VARCHAR(40);
