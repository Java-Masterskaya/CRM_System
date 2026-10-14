DO $$
    BEGIN
        IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'crm_app') THEN
            CREATE ROLE crm_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

GRANT crm_app TO CURRENT_USER;
