CREATE TABLE request_types (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    default_priority VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE request_types IS 'Справочник типов заявок; принадлежит арендатору, стартовых значений нет';
COMMENT ON COLUMN request_types.default_priority IS 'Приоритет по умолчанию для новых заявок этого типа; набор значений задаёт T-035';
COMMENT ON COLUMN request_types.active IS 'Отключённый тип недоступен для новых заявок, существующие заявки с ним читаются';

-- Название уникально внутри арендатора без учёта регистра: «Ремонт» и «ремонт» — один тип.
CREATE UNIQUE INDEX request_types_name_unique ON request_types (tenant_id, lower(name));
CREATE INDEX idx_request_types_tenant_active ON request_types (tenant_id, active);

-- До этой миграции тип заявки ничем не проверялся, а справочник создаётся выше пустым:
-- любая уже записанная ссылка указывает на несуществующий тип и не дала бы добавить ключ.
UPDATE requests SET type_id = NULL
WHERE type_id IS NOT NULL AND type_id NOT IN (SELECT id FROM request_types);

ALTER TABLE requests
    ADD CONSTRAINT requests_type_id_fkey FOREIGN KEY (type_id) REFERENCES request_types (id);

CREATE INDEX idx_requests_type_id ON requests (type_id);
