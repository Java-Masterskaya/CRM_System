-- Причина последней неудачной попытки доставки (T-067): короткий код для отбора и сообщение
-- для разбора. Сообщение формирует код приложения, а не внешний сервис, поэтому в нём нет
-- адресов, текста письма и токенов.
ALTER TABLE outbox_events
    ADD COLUMN last_error_code VARCHAR(50),
    ADD COLUMN last_error_message VARCHAR(500);

-- Событие не может оказаться в окончательном неуспехе без объяснения, почему.
ALTER TABLE outbox_events ADD CONSTRAINT outbox_events_failed_has_reason_check CHECK (
    status <> 'FAILED' OR last_error_code IS NOT NULL
);

COMMENT ON COLUMN outbox_events.last_error_code IS 'Код причины последней неудачной попытки, например NO_SENDER';
COMMENT ON COLUMN outbox_events.last_error_message IS 'Пояснение к причине без персональных данных';
