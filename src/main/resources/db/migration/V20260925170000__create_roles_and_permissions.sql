-- ============================================================================
-- T-020: Модель ролей и гранулярных прав
-- ============================================================================

-- 1. Таблица гранулярных прав (permissions)
-- Право определяется UUID и строковым кодом (например, REQUEST_STATUS_CHANGE, USER_MANAGE)
CREATE TABLE IF NOT EXISTS permissions (
    id          UUID PRIMARY KEY,
    code        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

-- 2. Таблица ролей (roles)
-- Роль — именованный набор прав (например, CLIENT, OPERATOR, ADMIN)
CREATE TABLE IF NOT EXISTS roles (
    id          UUID PRIMARY KEY,
    code        VARCHAR(50) NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(255),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_roles_code UNIQUE (code)
);

-- 3. Связь M2M: Роли <-> Права (role_permissions)
CREATE TABLE IF NOT EXISTS role_permissions (
    role_id       UUID NOT NULL,
    permission_id UUID NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role
        FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission
        FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

-- 4. Связь M2M: Пользователи <-> Роли (user_roles)
-- Внимание: Внешний ключ на users(id) не добавляем, так как таблица users создается в T-019
CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_role
        FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

-- Индексы для быстрой выборки прав и ролей
CREATE INDEX IF NOT EXISTS idx_role_permissions_role ON role_permissions (role_id);
CREATE INDEX IF NOT EXISTS idx_role_permissions_permission ON role_permissions (permission_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_user ON user_roles (user_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_role ON user_roles (role_id);
