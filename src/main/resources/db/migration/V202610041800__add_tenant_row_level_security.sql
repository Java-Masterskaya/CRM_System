CREATE OR REPLACE FUNCTION crm_tenant_visible(row_tenant_id uuid)
       RETURNS boolean
       LANGUAGE sql
       STABLE
AS $$
    SELECT CASE
       WHEN NULLIF(current_setting('app.tenant_id', true), '') IS NULL THEN true
       ELSE row_tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::uuid
END
$$;
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') THEN
        CREATE ROLE crm_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO crm_app;
GRANT crm_app TO CURRENT_USER;
GRANT SELECT, INSERT, UPDATE, DELETE ON
    requests,
    request_types,
    request_comments,
    request_audit_log,
    users,
    roles,
    permissions,
    tenant_settings,
    outbox_events,
    notifications
TO crm_app;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO crm_app;

DO $$
DECLARE
   tenant_table text;
BEGIN
    FOREACH tenant_table IN ARRAY ARRAY[
        'requests',
        'request_types',
        'request_comments',
        'request_audit_log',
        'users',
        'roles',
        'permissions',
        'tenant_settings',
        'outbox_events',
        'notifications'
    ]
    LOOP
            EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY', tenant_table);
            EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY', tenant_table);
            EXECUTE format('DROP POLICY IF EXISTS tenant_isolation ON %I', tenant_table);
            EXECUTE format('CREATE POLICY tenant_isolation ON %I FOR ALL TO PUBLIC ' ||
                           'USING (crm_tenant_visible(tenant_id)) ' ||
                           'WITH CHECK (crm_tenant_visible(tenant_id))', tenant_table);
    END LOOP;
END
$$;
