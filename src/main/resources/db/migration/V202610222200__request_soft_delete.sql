-- T-047: время мягкого удаления заявки и право только для администратора.

ALTER TABLE requests ADD COLUMN deleted_at TIMESTAMPTZ;

COMMENT ON COLUMN requests.deleted IS 'Мягкое удаление: запись остается, за выдачи скрыта';
COMMENT ON COLUMN requests.deleted_at IS 'Момент мягкого удаления; NULL, по умолчанию';

-- Для уже помечанных удаленными заявок момент не известен - ставим updated_at как нижнюю оценку.
UPDATE requests SET deleted_at = updated_at WHERE deleted AND deleted_at IS NULL;

INSERT INTO permissions (id, tenant_id, code, description)
SELECT gen_random_uuid(), t.id, 'REQUEST_DELETE', 'Мягкое удаление и востановление заявки'
FROM tenants AS t
WHERE t.id <> '00000000-0000-0000-0000-000000000000'
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO role_permissions (tenant_id, role_id, permission_id)
SELECT r.tenant_id, r.id, p.id
FROM roles AS r
JOIN permissions AS p ON p.tenant_id = r.tenant_id AND p.code = 'REQUEST_DELETE'
WHERE r.code = 'ADMIN'
ON CONFLICT (role_id, permission_id) DO NOTHING;

CREATE OR REPLACE FUNCTION seed_tenant_access_defaults(target_tenant_id UUID)
RETURNS VOID
LANGUAGE plpgsql
AS $$
   BEGIN
        IF target_tenant_id = '00000000-0000-0000-0000-000000000000' THEN
           RETURN;
        END IF;

        INSERT INTO permissions (id, tenant_id, code, description)
        VALUES
            (gen_random_uuid(), target_tenant_id, 'REQUEST_CREATE', 'Создание заявки'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_READ_OWN', 'Чтение свох заявок'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_READ_ALL', 'Чтение заявок арендатора'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_CANCEL_OWN', 'Отмена своей заявки'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_STATUS_CHANGE', 'Изменения статуса заявки'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_ASSIGN', 'Назвачения исполнителя заявки'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_EDIT', 'Изменения полей заявки'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_AUDIT_READ', 'Чтения аудита заявки'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_DELETE', 'Мягкое удаление и восстановление своей заявки'),
            (gen_random_uuid(), target_tenant_id, 'COMMENT_PUBLIC_CREATE_OWN', 'Публичный комментарий к своей заявке'),
            (gen_random_uuid(), target_tenant_id, 'COMMENT_PUBLIC_CREATE', 'Публичный комментарий оператора'),
            (gen_random_uuid(), target_tenant_id, 'COMMENT_INTERNAL_CREATE', 'Внутренний комментарий'),
            (gen_random_uuid(), target_tenant_id, 'ATTACHMENT_CREATE_OWN', 'Добавление вложения к своей заявке'),
            (gen_random_uuid(), target_tenant_id, 'ATTACHMENT_READ_OWN', 'Чтение вложений своей заявке'),
            (gen_random_uuid(), target_tenant_id, 'ATTACHMENT_MANAGE', 'Управление вложениями в административном контуре'),
            (gen_random_uuid(), target_tenant_id, 'PROFILE_READ_OWN', 'Чтение собственного профиля'),
            (gen_random_uuid(), target_tenant_id, 'PROFILE_EDIT_OWN', 'Изменения собственного профиля'),
            (gen_random_uuid(), target_tenant_id, 'USER_READ', 'Чтение пользователей арендатора'),
            (gen_random_uuid(), target_tenant_id, 'USER_MANAGE', 'Управление пользователями и назначение ролей'),
            (gen_random_uuid(), target_tenant_id, 'ROLE_MANAGE', 'Управление ролями арендатора'),
            (gen_random_uuid(), target_tenant_id, 'TENANT_SETTINGS_READ', 'Чтение настроек арендатора'),
            (gen_random_uuid(), target_tenant_id, 'TENANT_SETTINGS_MANAGE', 'Изменения настроек арендатора'),
            (gen_random_uuid(), target_tenant_id, 'REQUEST_TYPE_MANAGE', 'Управление типами заявок'),
            (gen_random_uuid(), target_tenant_id, 'SLA_POLICY_MANAGE', 'Управление SLA и рабочим календарем'),
            (gen_random_uuid(), target_tenant_id, 'INTEGRATION_MANAGE', 'Управление интеграциями арендатора'),
            (gen_random_uuid(), target_tenant_id, 'ANALYTICS_READ', 'Просмотр аналитики арендатора')
        ON CONFLICT (tenant_id, code) DO NOTHING;

        INSERT INTO roles (id, tenant_id, code, name, description)
        VALUES
            (gen_random_uuid(), target_tenant_id, 'CLIENT', 'Клиент', 'Пользователь клиентского контура'),
            (gen_random_uuid(), target_tenant_id, 'OPERATOR', 'Оператор', 'Оператор заявок арендатора'),
            (gen_random_uuid(), target_tenant_id, 'ADMIN', 'Администратор', 'Администратор арендатора')
        ON CONFLICT (tenant_id, code) DO NOTHING;

        INSERT INTO role_permissions (tenant_id, role_id, permission_id)
        SELECT r.tenant_id, r.id, p.id
        FROM roles r
         JOIN permissions p ON p.tenant_id = r.tenant_id
         JOIN (VALUES
                   ('CLIENT', 'REQUEST_CREATE'),
                   ('CLIENT', 'REQUEST_READ_OWN'),
                   ('CLIENT', 'REQUEST_CANCEL_OWN'),
                   ('CLIENT', 'COMMENT_PUBLIC_CREATE_OWN'),
                   ('CLIENT', 'ATTACHMENT_CREATE_OWN'),
                   ('CLIENT', 'ATTACHMENT_READ_OWN'),
                   ('CLIENT', 'PROFILE_READ_OWN'),
                   ('CLIENT', 'PROFILE_EDIT_OWN'),
                   ('OPERATOR', 'REQUEST_READ_ALL'),
                   ('OPERATOR', 'REQUEST_STATUS_CHANGE'),
                   ('OPERATOR', 'REQUEST_ASSIGN'),
                   ('OPERATOR', 'REQUEST_EDIT'),
                   ('OPERATOR', 'REQUEST_AUDIT_READ'),
                   ('OPERATOR', 'COMMENT_PUBLIC_CREATE'),
                   ('OPERATOR', 'COMMENT_INTERNAL_CREATE'),
                   ('OPERATOR', 'ATTACHMENT_MANAGE'),
                   ('OPERATOR', 'PROFILE_READ_OWN'),
                   ('OPERATOR', 'PROFILE_EDIT_OWN'),
                   ('ADMIN', 'REQUEST_READ_ALL'),
                   ('ADMIN', 'REQUEST_STATUS_CHANGE'),
                   ('ADMIN', 'REQUEST_ASSIGN'),
                   ('ADMIN', 'REQUEST_EDIT'),
                   ('ADMIN', 'REQUEST_AUDIT_READ'),
                   ('ADMIN', 'REQUEST_DELETE'),
                   ('ADMIN', 'COMMENT_PUBLIC_CREATE'),
                   ('ADMIN', 'COMMENT_INTERNAL_CREATE'),
                   ('ADMIN', 'ATTACHMENT_MANAGE'),
                   ('ADMIN', 'PROFILE_READ_OWN'),
                   ('ADMIN', 'PROFILE_EDIT_OWN'),
                   ('ADMIN', 'USER_READ'),
                   ('ADMIN', 'USER_MANAGE'),
                   ('ADMIN', 'ROLE_MANAGE'),
                   ('ADMIN', 'TENANT_SETTINGS_READ'),
                   ('ADMIN', 'TENANT_SETTINGS_MANAGE'),
                   ('ADMIN', 'REQUEST_TYPE_MANAGE'),
                   ('ADMIN', 'SLA_POLICY_MANAGE'),
                   ('ADMIN', 'INTEGRATION_MANAGE'),
                   ('ADMIN', 'ANALYTICS_READ')
        ) AS grants(role_code, permission_code) ON grants.permission_code = p.code
        WHERE r.tenant_id = target_tenant_id
          AND r.code = grants.role_code
        ON CONFLICT (role_id, permission_id) DO NOTHING;
    END;
$$;
