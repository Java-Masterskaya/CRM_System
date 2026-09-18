CREATE TABLE requests (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    type_id UUID,
    subject VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    data_params JSONB,
    priority VARCHAR(20),
    status VARCHAR(20) NOT NULL,
    author_id UUID NOT NULL,
    assignee_id UUID,
    desired_due_at TIMESTAMPTZ,
    first_response_due_at TIMESTAMPTZ,
    resolution_due_at TIMESTAMPTZ,
    overdue BOOLEAN NOT NULL DEFAULT FALSE,
    deleted BOOLEAN NOT NULL DEFAULT FALSE,
    version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE requests IS 'Заявки на обработку данных';
COMMENT ON COLUMN requests.type_id IS 'Тип заявки; внешний ключ добавляется вместе со справочником типов (T-034)';
COMMENT ON COLUMN requests.author_id IS 'Клиент, создавший заявку; внешний ключ добавляется вместе с таблицей пользователей';
COMMENT ON COLUMN requests.assignee_id IS 'Оператор-исполнитель; внешний ключ добавляется вместе с таблицей пользователей';
COMMENT ON COLUMN requests.overdue IS 'Признак нарушения срока; на жизненный цикл не влияет';
COMMENT ON COLUMN requests.version IS 'Версия для оптимистичной блокировки';

CREATE INDEX idx_requests_tenant_status ON requests (tenant_id, status);
CREATE INDEX idx_requests_tenant_assignee ON requests (tenant_id, assignee_id);
CREATE INDEX idx_requests_tenant_author ON requests (tenant_id, author_id);
CREATE INDEX idx_requests_tenant_resolution_due_at ON requests (tenant_id, resolution_due_at);
CREATE INDEX idx_requests_tenant_overdue ON requests (tenant_id, overdue);
CREATE INDEX idx_requests_tenant_created_at ON requests (tenant_id, created_at DESC);

CREATE INDEX idx_requests_search ON requests
    USING GIN (to_tsvector('russian', subject || ' ' || description));
