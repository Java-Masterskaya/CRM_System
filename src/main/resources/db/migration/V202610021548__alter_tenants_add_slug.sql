ALTER TABLE tenants
    ADD COLUMN slug VARCHAR(100);

UPDATE tenants
SET slug = 'legacy_' || id;

UPDATE tenants
SET slug = 'tenant_1'
WHERE id = '00000000-0000-0000-0000-000000000001';

UPDATE tenants
SET slug = 'tenant_2'
WHERE id = '00000000-0000-0000-0000-000000000002';

ALTER TABLE tenants
    ALTER COLUMN slug SET NOT NULL;

CREATE UNIQUE INDEX uk_tenants_slug ON tenants (slug);
