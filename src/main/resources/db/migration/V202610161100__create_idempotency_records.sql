
CREATE TABLE idempotency_records (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL,
    user_id UUID NOT NULL,
    operation VARCHAR(100) NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash VARCHAR(64) NOT NULL,
    response_status SMALLINT,
    response_body TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,

    CONSTRAINT fk_idempotency_records_tenant
        FOREIGN KEY (tenant_id) REFERENCES tenants (id),

    CONSTRAINT fk_idempotency_records_user
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id),

    CONSTRAINT uk_idempotency_records_scope
        UNIQUE (tenant_id, user_id, operation, idempotency_key),

    CONSTRAINT ck_idempotency_records_response
        CHECK (
            (response_status IS NULL AND response_body IS NULL)
            OR
            (response_status IS NOT NULL AND response_body IS NOT NULL)
        )
);

COMMENT ON TABLE idempotency_records IS
    'Результаты изменяющих операций для безопасного повторения запросов';

COMMENT ON COLUMN idempotency_records.request_hash IS
    'SHA-256 нормализованного содержимого запроса';

COMMENT ON COLUMN idempotency_records.response_body IS
    'Снимок тела первоначального ответа';

CREATE INDEX idx_idempotency_records_created_at
    ON idempotency_records (created_at);

ALTER TABLE idempotency_records ENABLE ROW LEVEL SECURITY;
ALTER TABLE idempotency_records FORCE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation
    ON idempotency_records
    FOR ALL
    TO PUBLIC
    USING (crm_tenant_visible(tenant_id))
    WITH CHECK (crm_tenant_visible(tenant_id));
