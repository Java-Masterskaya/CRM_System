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

COMMENT ON FUNCTION crm_tenant_visible(uuid) IS
        'true для всех строк пока app.tenant_id пуст (фон и забытый контекст). Иначе только свой арендатор';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') THEN
        BEGIN
            CREATE ROLE crm_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
        EXCEPTION
            WHEN insufficient_privilege THEN
                NULL;
        END;
    END IF;

    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') THEN
       BEGIN
            EXECUTE format('GRANT crm_app TO %I', current_user);
       EXCEPTION
            WHEN insufficient_privilege THEN
                NULL;
       END;
       EXECUTE 'GRANT USAGE ON SCHEMA public TO crm_app';
       EXECUTE 'GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO crm_app';
       EXECUTE 'ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO crm_app';
    END IF;
END
$$;

CREATE OR REPLACE FUNCTION crm_apply_tenant_rls()
       RETURNS void
       LANGUAGE plpgsql

AS $$
DECLARE
   tenant_table text;
BEGIN
    FOR tenant_table IN
        SELECT c.table_name
        FROM information_schema.columns c
        WHERE c.table_schema = 'public'
            AND c.column_name = 'tenant_id'
            AND c.table_name <> 'tenants'
        GROUP BY c.table_name
        ORDER BY c.table_name
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

SELECT crm_apply_tenant_rls();
