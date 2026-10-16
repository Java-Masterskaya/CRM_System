-- Время назначения исполнителя (T-048). Колонка assignee_id уже есть с T-033;
-- здесь добавляется момент, когда исполнитель был назначен.
ALTER TABLE requests ADD COLUMN assigned_at TIMESTAMPTZ;

-- Заявки, у которых исполнитель уже стоит, получают лучшее доступное приближение —
-- время последнего изменения.
UPDATE requests SET assigned_at = updated_at
WHERE assignee_id IS NOT NULL AND assigned_at IS NULL;

-- Исполнитель и время назначения появляются и исчезают только вместе: разойтись им не даст
-- даже прямая запись в базу в обход кода.
ALTER TABLE requests ADD CONSTRAINT requests_assignment_check CHECK (
    (assignee_id IS NULL) = (assigned_at IS NULL)
    );

COMMENT ON COLUMN requests.assigned_at IS 'Момент назначения текущего исполнителя; NULL, если исполнителя нет';
