-- Эскалация просроченных заявок (T-061, ТЗ §6.4): о нарушении срока узнают исполнитель и
-- администраторы арендатора — по одному разу на каждый срок.
--
-- Признак «эскалация отправлена» хранится отдельно от признака просрочки (T-060): просрочка —
-- факт о заявке, а эскалация — отметка фоновой проверки, что письма о нём записаны. Именно она
-- делает повторный проход безопасным: эскалированная заявка второй раз в выборку не попадает.
-- Сущность заявки эти колонки не отображает: сохранение заявки их не трогает, и версия заявки
-- при эскалации не растёт.
ALTER TABLE requests
    ADD COLUMN first_response_escalated BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN resolution_escalated BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN requests.first_response_escalated IS 'Письма о просрочке первого ответа записаны';
COMMENT ON COLUMN requests.resolution_escalated IS 'Письма о просрочке решения записаны';

-- Заявки, помеченные просроченными до появления эскалации, считаются уже эскалированными:
-- иначе первый же проход после выкладки разослал бы письма о давно истёкших сроках.
UPDATE requests
SET first_response_escalated = first_response_overdue,
    resolution_escalated = resolution_overdue
WHERE first_response_overdue OR resolution_overdue;

-- Выборка эскалации: просрочено, но ещё не эскалировано, по порядку сроков. Частичные индексы
-- содержат только такие заявки, и после эскалации заявка из них выпадает. Условия совпадают с
-- RequestRepository.lockFirstResponseToEscalate и lockResolutionToEscalate — менять только
-- вместе.
CREATE INDEX idx_requests_first_response_escalation
    ON requests (first_response_due_at)
    WHERE first_response_overdue AND NOT first_response_escalated AND NOT deleted;

CREATE INDEX idx_requests_resolution_escalation
    ON requests (resolution_due_at)
    WHERE resolution_overdue AND NOT resolution_escalated AND NOT deleted;
