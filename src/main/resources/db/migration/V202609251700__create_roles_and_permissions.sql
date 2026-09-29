-- ============================================================================
-- T-020: Модель ролей и гранулярных прав
-- ============================================================================

-- 1. Таблица гранулярных прав (permissions)
-- Право определяется UUID и строковым кодом
-- (например, REQUEST_STATUS_CHANGE, USER_MANAGE)
CREATE TABLE permissions (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    code          VARCHAR(100) NOT NULL,
    description   VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_permissions_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenants (id),

    CONSTRAINT uk_permissions_tenant_code
        UNIQUE (tenant_id, code)
);

-- 2. Таблица ролей (roles)
-- Роль — именованный набор прав
-- (например, CLIENT, OPERATOR, ADMIN)
CREATE TABLE roles (
    id            UUID PRIMARY KEY,
    tenant_id     UUID NOT NULL,
    code          VARCHAR(50) NOT NULL,
    name          VARCHAR(100) NOT NULL,
    description   VARCHAR(255),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT fk_roles_tenant
        FOREIGN KEY (tenant_id)
        REFERENCES tenants (id),

    CONSTRAINT uk_roles_tenant_code
        UNIQUE (tenant_id, code)
);

-- 3. Связь M2M: Роли <-> Права (role_permissions)
CREATE TABLE role_permissions (
    role_id       UUID NOT NULL,
    permission_id UUID NOT NULL,

    PRIMARY KEY (role_id, permission_id),

    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id)
        REFERENCES roles (id)
        ON DELETE CASCADE,

    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id)
        REFERENCES permissions (id)
        ON DELETE CASCADE
);

-- 4. Связь M2M: Пользователи <-> Роли (user_roles)
-- Внешний ключ на users(id) не добавляем,
-- так как таблица users создается в T-019.
CREATE TABLE user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,

    PRIMARY KEY (user_id, role_id),

    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id)
        REFERENCES roles (id)
        ON DELETE CASCADE
);

-- Индексы для обратного поиска
CREATE INDEX idx_role_permissions_permission
    ON role_permissions (permission_id);

CREATE INDEX idx_user_roles_role
    ON user_roles (role_id);

-- ============================================================================
-- Seed-данные: Дефолтные системные права и роли
-- ============================================================================

-- Системный tenant для дефолтных ролей и прав
INSERT INTO tenants (
    id,
    name,
    active,
    created_at,
    updated_at
) VALUES (
    '00000000-0000-0000-0000-000000000000',
    'System',
    TRUE,
    NOW(),
    NOW()
)
ON CONFLICT (id) DO NOTHING;

-- Системный UUID для дефолтного tenant
INSERT INTO permissions (id, tenant_id, code, description) VALUES
    ('11111111-1111-1111-1111-111111111111',
     '00000000-0000-0000-0000-000000000000',
     'REQUEST_STATUS_CHANGE',
     'Право изменять статус заявки'),
    ('22222222-2222-2222-2222-222222222222',
     '00000000-0000-0000-0000-000000000000',
     'USER_MANAGE',
     'Право управления пользователями')
ON CONFLICT (tenant_id, code) DO NOTHING;
