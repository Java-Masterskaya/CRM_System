INSERT INTO tenants (id, name, active, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000001',
     'Dev Tenant',
     TRUE,
     now(),
     now())
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenants (id, name, active, created_at, updated_at) VALUES
    ('00000000-0000-0000-0000-000000000002',
     'Test Tenant',
     TRUE,
     now(),
     now())
ON CONFLICT (id) DO NOTHING;
