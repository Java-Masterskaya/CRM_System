ALTER TABLE tenants
    ADD COLUMN slug VARCHAR(255);

ALTER TABLE tenants
    ADD CONSTRAINT uk_tenants_slug UNIQUE (slug);
