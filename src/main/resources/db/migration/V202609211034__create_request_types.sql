CREATE TABLE request_types (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    default_priority VARCHAR(20),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT request_types_name_unique UNIQUE (tenant_id, name)
);

COMMENT ON TABLE request_types IS 'Справочник типов заявок; принадлежит арендатору, стартовых значений нет';
COMMENT ON COLUMN request_types.default_priority IS 'Приоритет по умолчанию для новых заявок этого типа; набор значений задаёт T-035';
COMMENT ON COLUMN request_types.active IS 'Отключённый тип недоступен для новых заявок, существующие заявки с ним читаются';

CREATE INDEX idx_request_types_tenant_active ON request_types (tenant_id, active);

ALTER TABLE requests
    ADD CONSTRAINT requests_type_id_fkey FOREIGN KEY (type_id) REFERENCES request_types (id);
