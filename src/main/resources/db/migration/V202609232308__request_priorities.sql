-- Приоритеты заявок (T-035): закрытый набор LOW, NORMAL, HIGH, URGENT и ранг для сортировки.
-- До этой миграции колонки приоритета были свободной строкой, поэтому сначала приводим к набору
-- то, что могло в них оказаться.

UPDATE requests SET priority = 'NORMAL'
WHERE priority IS NULL OR priority NOT IN ('LOW', 'NORMAL', 'HIGH', 'URGENT');

ALTER TABLE requests ADD COLUMN priority_rank SMALLINT;

UPDATE requests SET priority_rank = CASE priority
    WHEN 'LOW' THEN 1
    WHEN 'NORMAL' THEN 2
    WHEN 'HIGH' THEN 3
    WHEN 'URGENT' THEN 4
END;

-- Значение по умолчанию то же, что у сущности (RequestPriority.FALLBACK): строка, вставленная
-- в обход кода без приоритета, получает согласованную пару NORMAL и 2, а не ошибку.
ALTER TABLE requests
    ALTER COLUMN priority SET NOT NULL,
    ALTER COLUMN priority SET DEFAULT 'NORMAL',
    ALTER COLUMN priority_rank SET NOT NULL,
    ALTER COLUMN priority_rank SET DEFAULT 2;

-- Одно ограничение проверяет и допустимость значения, и то, что ранг ему соответствует:
-- разойтись им не даст даже прямая запись в базу в обход кода.
ALTER TABLE requests ADD CONSTRAINT requests_priority_check CHECK (
    (priority, priority_rank) IN (('LOW', 1), ('NORMAL', 2), ('HIGH', 3), ('URGENT', 4))
);

COMMENT ON COLUMN requests.priority IS 'Приоритет: LOW, NORMAL, HIGH, URGENT';
COMMENT ON COLUMN requests.priority_rank IS 'Ранг приоритета для сортировки по срочности, от 1 (LOW) до 4 (URGENT)';

-- Реестр и очередь работ сортируют и фильтруют по срочности в пределах арендатора.
CREATE INDEX idx_requests_tenant_priority_rank ON requests (tenant_id, priority_rank DESC);

UPDATE request_types SET default_priority = NULL
WHERE default_priority NOT IN ('LOW', 'NORMAL', 'HIGH', 'URGENT');

ALTER TABLE request_types ADD CONSTRAINT request_types_default_priority_check CHECK (
    default_priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')
);

COMMENT ON COLUMN request_types.default_priority IS 'Приоритет по умолчанию для новых заявок этого типа: LOW, NORMAL, HIGH, URGENT; не задан — NORMAL';
