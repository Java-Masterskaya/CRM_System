-- История переходов статусов заявки (T-044, ТЗ §5.2): откуда, куда, кто, когда и комментарий.
-- Запись делается в той же транзакции, что и смена статуса, и только добавляется.
CREATE TABLE request_status_history (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    request_id UUID NOT NULL,
    from_status VARCHAR(20) NOT NULL,
    to_status VARCHAR(20) NOT NULL,
    author_id UUID NOT NULL REFERENCES users (id),
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Запись принадлежит тому же арендатору, что и заявка: привязать её к заявке другого
    -- арендатора база не даст.
    CONSTRAINT request_status_history_request_fkey
        FOREIGN KEY (tenant_id, request_id) REFERENCES requests (tenant_id, id),
    -- Набор статусов — как в requests_status_check; переход всегда меняет статус.
    CONSTRAINT request_status_history_status_check CHECK (
        from_status IN ('NEW', 'CONTACTED', 'IN_PROGRESS', 'ON_HOLD', 'DONE', 'REJECTED',
                        'CANCELLED')
        AND to_status IN ('NEW', 'CONTACTED', 'IN_PROGRESS', 'ON_HOLD', 'DONE', 'REJECTED',
                          'CANCELLED')
        AND from_status <> to_status
    ),
    CONSTRAINT request_status_history_comment_length_check CHECK (
        comment IS NULL OR char_length(comment) BETWEEN 1 AND 4000
    ),
    -- Отклонение без причины не записывается даже в обход приложения (ТЗ §5.2).
    CONSTRAINT request_status_history_rejection_reason_check CHECK (
        to_status <> 'REJECTED' OR comment IS NOT NULL
    )
);

COMMENT ON TABLE request_status_history IS 'История переходов статусов заявки; только для чтения';
COMMENT ON COLUMN request_status_history.from_status IS 'Статус до перехода';
COMMENT ON COLUMN request_status_history.to_status IS 'Статус после перехода';
COMMENT ON COLUMN request_status_history.author_id IS 'Кто перевёл заявку';
COMMENT ON COLUMN request_status_history.comment IS 'Комментарий к переходу; для REJECTED — причина, обязательна';
COMMENT ON COLUMN request_status_history.created_at IS 'Когда заявка переведена';

-- Под историю одной заявки по времени (карточки T-038, T-041); tenant_id первым, как в остальных
-- индексах арендаторских таблиц.
CREATE INDEX idx_request_status_history_tenant_request_created_at
    ON request_status_history (tenant_id, request_id, created_at);

-- История только для чтения, как журнал аудита: запись нельзя ни изменить, ни удалить даже в
-- обход приложения. Очистка тестовой базы через TRUNCATE этот триггер не вызывает.
CREATE FUNCTION request_status_history_reject_change() RETURNS TRIGGER AS $$
BEGIN
    RAISE EXCEPTION 'request_status_history: история статусов только для чтения (id=%)', OLD.id
        USING ERRCODE = 'integrity_constraint_violation';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER request_status_history_read_only
    BEFORE UPDATE OR DELETE ON request_status_history
    FOR EACH ROW EXECUTE FUNCTION request_status_history_reject_change();

-- Изоляция арендаторов на уровне строк (T-015) распространяется на новую таблицу с tenant_id.
SELECT crm_apply_tenant_rls();
