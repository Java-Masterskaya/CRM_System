ALTER TABLE tenants
    ADD COLUMN slug VARCHAR(63);

UPDATE tenants
SET slug = 'legacy-' || id;

UPDATE tenants
SET slug = 'tenant-1'
WHERE id = '00000000-0000-0000-0000-000000000001';

UPDATE tenants
SET slug = 'tenant-2'
WHERE id = '00000000-0000-0000-0000-000000000002';

ALTER TABLE tenants
    ALTER COLUMN slug SET NOT NULL;

ALTER TABLE tenants
    ADD CONSTRAINT chk_tenants_slug_format
        CHECK (slug ~ '^[a-z0-9-]{2,63}$');

ALTER TABLE tenants
    ADD CONSTRAINT uk_tenants_slug
        UNIQUE (slug);
