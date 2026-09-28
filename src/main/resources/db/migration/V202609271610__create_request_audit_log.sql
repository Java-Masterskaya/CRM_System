-- Журнал аудита изменений заявки (T-046, ТЗ §6.7): какое значимое поле менялось, было, стало,
-- кто и когда. Запись делается в той же транзакции, что и само изменение, и только добавляется.
CREATE TABLE request_audit_log (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    request_id UUID NOT NULL REFERENCES requests (id),
    field VARCHAR(50) NOT NULL,
    old_value TEXT,
    new_value TEXT,
    -- Как и requests.author_id, без внешнего ключа: таблицы пользователей ещё нет (T-019).
    author_id UUID NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE request_audit_log IS 'Журнал аудита изменений значимых полей заявки; только для чтения';
COMMENT ON COLUMN request_audit_log.field IS 'Изменённое поле, например PRIORITY или DESCRIPTION';
COMMENT ON COLUMN request_audit_log.old_value IS 'Значение до изменения; NULL — поле было пустым';
COMMENT ON COLUMN request_audit_log.new_value IS 'Значение после изменения; NULL — поле очищено';
COMMENT ON COLUMN request_audit_log.author_id IS 'Кто изменил поле';
COMMENT ON COLUMN request_audit_log.created_at IS 'Когда поле изменено';

-- tenant_id первым, как в остальных индексах арендаторских таблиц: журнал читается только в
-- пределах арендатора.
CREATE INDEX idx_request_audit_log_tenant_request_created_at
    ON request_audit_log (tenant_id, request_id, created_at);

-- Журнал только для чтения: запись нельзя ни изменить, ни удалить даже в обход приложения.
-- Очистка тестовой базы через TRUNCATE этот триггер не вызывает.
CREATE FUNCTION request_audit_log_reject_change() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'request_audit_log: журнал аудита только для чтения (id=%)', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER request_audit_log_read_only
    BEFORE UPDATE OR DELETE ON request_audit_log
    FOR EACH ROW EXECUTE FUNCTION request_audit_log_reject_change();
