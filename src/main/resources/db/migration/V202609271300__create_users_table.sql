-- ============================================================================
-- T-019: Модель пользователей
-- ============================================================================

-- 1. Создаем таблицу пользователей (users)
CREATE TABLE users (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    status        VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
    deleted_at    TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_users_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenants (id),

        CONSTRAINT chk_users_status
        CHECK (status IN ('ACTIVE', 'BLOCKED'))
);

-- 2. Составной уникальный индекс: email уникален строго внутри одного tenant_id
CREATE UNIQUE INDEX uk_users_tenant_email
    ON users (tenant_id, lower(email));
