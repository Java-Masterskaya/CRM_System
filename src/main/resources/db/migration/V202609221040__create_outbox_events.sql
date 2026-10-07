CREATE TABLE outbox_events (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    status VARCHAR(20) NOT NULL CONSTRAINT outbox_events_status_check CHECK (
        status IN ('NEW', 'IN_PROGRESS', 'SENT', 'FAILED')
    ),
    attempts INTEGER NOT NULL DEFAULT 0 CONSTRAINT outbox_events_attempts_check CHECK (
        attempts >= 0
    ),
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

COMMENT ON TABLE outbox_events IS 'Исходящие события: пишутся в одной транзакции с изменением, доставляются отдельным процессом';
COMMENT ON COLUMN outbox_events.payload IS 'Полезная нагрузка события; после записи не меняется — защищено триггером';
COMMENT ON COLUMN outbox_events.next_attempt_at IS 'Момент, раньше которого запись не берут в обработку';

CREATE INDEX idx_outbox_events_status_next_attempt ON outbox_events (status, next_attempt_at);
CREATE INDEX idx_outbox_events_tenant_created_at ON outbox_events (tenant_id, created_at DESC);

CREATE FUNCTION outbox_events_reject_payload_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id THEN
        RAISE EXCEPTION 'outbox_events: событие неизменяемо после записи (id=%)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER outbox_events_immutable_payload
    BEFORE UPDATE ON outbox_events
    FOR EACH ROW EXECUTE FUNCTION outbox_events_reject_payload_change();
