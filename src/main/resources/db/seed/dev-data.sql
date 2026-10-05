INSERT INTO tenants (id, slug, name, active, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001',
     'tenant_1',
     'Dev Tenant',
     TRUE,
     now(),
     now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenants (id, slug, name, active, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000002',
     'tenant_2',
     'Test Tenant',
     TRUE,
     now(),
     now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_settings (tenant_id, timezone, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001',
     'Europe/Moscow',
     now(),
     now())
ON CONFLICT (tenant_id) DO NOTHING;

INSERT INTO tenant_settings (tenant_id, timezone, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000002',
     'America/Los_Angeles',
     now(),
     now())
ON CONFLICT (tenant_id) DO NOTHING;
