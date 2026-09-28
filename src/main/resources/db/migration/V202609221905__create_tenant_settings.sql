CREATE TABLE tenant_settings
(
    tenant_id UUID PRIMARY KEY REFERENCES tenants(id),
    timezone VARCHAR(64) NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);
