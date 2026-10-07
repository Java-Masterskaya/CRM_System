-- Уведомления и результат их доставки (T-069). Строку заводит отправитель писем при первой
-- попытке доставить событие из outbox и обновляет при каждой следующей.
CREATE TABLE notifications (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    -- Доставленные события будут удаляться из очереди (#147), а уведомление должно остаться.
    outbox_event_id UUID REFERENCES outbox_events (id) ON DELETE SET NULL,
    notification_type VARCHAR(50) NOT NULL,
    recipient_email VARCHAR(320) NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT notifications_status_check CHECK (
        status IN ('SENT', 'FAILED')
    ),
    attempts INTEGER NOT NULL CONSTRAINT notifications_attempts_check CHECK (attempts >= 1),
    last_error_code VARCHAR(50),
    sent_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Одно событие — одно уведомление: повторная попытка находит строку и обновляет её.
    CONSTRAINT notifications_outbox_event_unique UNIQUE (outbox_event_id),
    CONSTRAINT notifications_failed_has_reason_check CHECK (
        status <> 'FAILED' OR last_error_code IS NOT NULL
    ),
    CONSTRAINT notifications_sent_has_time_check CHECK (
        status <> 'SENT' OR sent_at IS NOT NULL
    )
);

COMMENT ON TABLE notifications IS 'Уведомления и результат последней попытки их доставки';
COMMENT ON COLUMN notifications.status IS 'Результат последней попытки: SENT — письмо принял почтовый сервер, FAILED — не принял; будет ли повтор, решает очередь outbox';
COMMENT ON COLUMN notifications.attempts IS 'Номер последней попытки доставки';
COMMENT ON COLUMN notifications.last_error_code IS 'Код причины последней неудачи, например MAIL_SERVER_UNAVAILABLE';
COMMENT ON COLUMN notifications.sent_at IS 'Когда почтовый сервер последний раз принял письмо';

CREATE INDEX idx_notifications_tenant_created_at ON notifications (tenant_id, created_at DESC);
