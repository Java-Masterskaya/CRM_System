-- Ссылка события на объект, к которому оно относится (#146): вид объекта и его идентификатор.
-- По ней выбираются все события одного объекта — для разбора и, если понадобится, для доставки
-- по порядку. Колонки допускают NULL: у уже записанных событий и у событий, не относящихся
-- к конкретному объекту, ссылки нет, а придуманное значение для них было бы неправдой.
ALTER TABLE outbox_events
    ADD COLUMN aggregate_type VARCHAR(100),
    ADD COLUMN aggregate_id UUID;

-- Ссылка задаётся целиком или не задаётся вовсе.
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_aggregate_complete_check CHECK (
    (aggregate_type IS NULL) = (aggregate_id IS NULL)
);

COMMENT ON COLUMN outbox_events.aggregate_type IS 'Вид объекта, к которому относится событие, например REQUEST';
COMMENT ON COLUMN outbox_events.aggregate_id IS 'Идентификатор объекта, к которому относится событие';

CREATE INDEX idx_outbox_events_aggregate ON outbox_events (aggregate_type, aggregate_id, created_at);

-- Ссылка, как и остальное содержимое события, после записи не меняется: к проверкам триггера
-- outbox_events_immutable_payload добавляются обе колонки. Сам триггер не пересоздаётся —
-- он вызывает функцию по имени.
CREATE OR REPLACE FUNCTION outbox_events_reject_payload_change() RETURNS TRIGGER AS $$
BEGIN
    IF NEW.payload IS DISTINCT FROM OLD.payload
        OR NEW.event_type IS DISTINCT FROM OLD.event_type
        OR NEW.tenant_id IS DISTINCT FROM OLD.tenant_id
        OR NEW.aggregate_type IS DISTINCT FROM OLD.aggregate_type
        OR NEW.aggregate_id IS DISTINCT FROM OLD.aggregate_id THEN
        RAISE EXCEPTION 'outbox_events: событие неизменяемо после записи (id=%)', OLD.id
            USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
