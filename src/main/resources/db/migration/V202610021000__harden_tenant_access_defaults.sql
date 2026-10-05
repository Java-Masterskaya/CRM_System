-- Донастройка по T-021: обезопасить выдачу прав в рамках одного арендатора
-- (согласованность по tenant_id) и исключить синтетического арендатора System из модели авторизации.

alter table roles
    add constraint uk_roles_tenant_id unique (tenant_id, id);

alter table permissions
    add constraint uk_permissions_tenant_id unique (tenant_id, id);

alter table users
    add constraint uk_users_tenant_id unique (tenant_id, id);

-- Заполнить принадлежность к арендатору (tenant_id)
-- для существующих привязок прав к ролям, используя арендатора самой роли.
alter table role_permissions add column tenant_id uuid;

update role_permissions rp
set tenant_id = r.tenant_id
from roles r
where r.id = rp.role_id;

-- Удалить существующие привязки, ведущие к чужим правам.
delete from role_permissions rp
using permissions p
where p.id = rp.permission_id
  and p.tenant_id <> rp.tenant_id;

alter table role_permissions alter column tenant_id set not null;
alter table role_permissions drop constraint fk_role_permissions_role;
alter table role_permissions drop constraint fk_role_permissions_permission;
alter table role_permissions
    add constraint fk_role_permissions_role
        foreign key (tenant_id, role_id)
        references roles (tenant_id, id)
        on delete cascade,
    add constraint fk_role_permissions_permission
        foreign key (tenant_id, permission_id)
        references permissions (tenant_id, id)
        on delete cascade;

create index idx_role_permissions_tenant_permission
    on role_permissions (tenant_id, permission_id);

-- Доназначить принадлежность к арендаторам для существующих назначений ролей пользователям,
-- а также удалить устаревшие «сиротские» строки и записи с правами из разных арендаторов (кросс-арендаторские связи).
alter table user_roles add column tenant_id uuid;

update user_roles ur
set tenant_id = r.tenant_id
from roles r
where r.id = ur.role_id;

delete from user_roles ur
where not exists (
    select 1
    from users u
    join roles r on r.id = ur.role_id and r.tenant_id = u.tenant_id
    where u.id = ur.user_id
);

alter table user_roles alter column tenant_id set not null;
alter table user_roles drop constraint fk_user_roles_role;
alter table user_roles
    add constraint fk_user_roles_role
        foreign key (tenant_id, role_id)
        references roles (tenant_id, id)
        on delete cascade,
    add constraint fk_user_roles_user
        foreign key (tenant_id, user_id)
        references users (tenant_id, id)
        on delete cascade;

create index idx_user_roles_tenant_role
    on user_roles (tenant_id, role_id);

-- Арендатор System являлся лишь заглушкой для двух прав доступа.
-- Он не должен оставаться активным арендатором с административными правами.
delete from role_permissions rp
using roles r
where r.id = rp.role_id
  and r.tenant_id = '00000000-0000-0000-0000-000000000000';

delete from roles
where tenant_id = '00000000-0000-0000-0000-000000000000';

delete from permissions
where tenant_id = '00000000-0000-0000-0000-000000000000';

update tenants
set active = false
where id = '00000000-0000-0000-0000-000000000000';

-- Теперь TenantService явно наполняет арендатора правами в той же транзакции.
-- Функция остаётся для идемпотентного обратного наполнения и восстановления данных.
drop trigger if exists trg_seed_tenant_access_defaults on tenants;
drop function if exists seed_new_tenant_access_defaults();

create or replace FUNCTION seed_tenant_access_defaults(target_tenant_id UUID)
RETURNS VOID
language plpgsql
AS $$
BEGIN
    IF target_tenant_id = '00000000-0000-0000-0000-000000000000' THEN
        RETURN;
    END IF;

    INSERT INTO permissions (id, tenant_id, code, description)
    VALUES
        (gen_random_uuid(), target_tenant_id, 'REQUEST_CREATE', 'Создание заявки'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_READ_OWN', 'Чтение своих заявок'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_READ_ALL', 'Чтение заявок арендатора'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_CANCEL_OWN', 'Отмена своей заявки'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_STATUS_CHANGE', 'Изменение статуса заявки'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_ASSIGN', 'Назначение исполнителя заявки'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_EDIT', 'Изменение полей заявки'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_AUDIT_READ', 'Чтение аудита заявки'),
        (gen_random_uuid(), target_tenant_id, 'COMMENT_PUBLIC_CREATE_OWN', 'Публичный комментарий к своей заявке'),
        (gen_random_uuid(), target_tenant_id, 'COMMENT_PUBLIC_CREATE', 'Публичный комментарий оператора'),
        (gen_random_uuid(), target_tenant_id, 'COMMENT_INTERNAL_CREATE', 'Внутренний комментарий'),
        (gen_random_uuid(), target_tenant_id, 'ATTACHMENT_CREATE_OWN', 'Добавление вложения к своей заявке'),
        (gen_random_uuid(), target_tenant_id, 'ATTACHMENT_READ_OWN', 'Чтение вложений своей заявки'),
        (gen_random_uuid(), target_tenant_id, 'ATTACHMENT_MANAGE', 'Управление вложениями в административном контуре'),
        (gen_random_uuid(), target_tenant_id, 'PROFILE_READ_OWN', 'Чтение собственного профиля'),
        (gen_random_uuid(), target_tenant_id, 'PROFILE_EDIT_OWN', 'Изменение собственного профиля'),
        (gen_random_uuid(), target_tenant_id, 'USER_READ', 'Чтение пользователей арендатора'),
        (gen_random_uuid(), target_tenant_id, 'USER_MANAGE', 'Управление пользователями и назначение ролей'),
        (gen_random_uuid(), target_tenant_id, 'ROLE_MANAGE', 'Управление ролями арендатора'),
        (gen_random_uuid(), target_tenant_id, 'TENANT_SETTINGS_READ', 'Чтение настроек арендатора'),
        (gen_random_uuid(), target_tenant_id, 'TENANT_SETTINGS_MANAGE', 'Изменение настроек арендатора'),
        (gen_random_uuid(), target_tenant_id, 'REQUEST_TYPE_MANAGE', 'Управление типами заявок'),
        (gen_random_uuid(), target_tenant_id, 'SLA_POLICY_MANAGE', 'Управление SLA и рабочим календарём'),
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
