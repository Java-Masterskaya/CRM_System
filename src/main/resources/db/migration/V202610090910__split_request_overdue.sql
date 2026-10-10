-- Просрочка заявки раздельно по двум срокам (T-060, ТЗ §6.4, SPEC §3.7). Срок первого ответа и
-- срок решения истекают независимо, и эскалация (T-061) и метрики (T-063) должны их различать.
--
-- Прежний общий признак overdue ставить было некому: фоновой проверки до этой задачи не было.
-- Он становится признаком просрочки решения — главного срока заявки, и значения сохраняются.
-- Просрочку первого ответа фоновая проверка проставит сама при первом проходе.
ALTER TABLE requests RENAME COLUMN overdue TO resolution_overdue;
ALTER TABLE requests ADD COLUMN first_response_overdue BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN requests.resolution_overdue IS
    'Срок решения истёк, пока заявка не была завершена; на жизненный цикл не влияет';
COMMENT ON COLUMN requests.first_response_overdue IS
    'Срок первого ответа истёк, пока заявка была в NEW; на жизненный цикл не влияет';

-- Фильтр реестра «просроченные» (T-040): просрочка по любому из сроков. Просроченных заявок
-- немного, поэтому индекс частичный — только они, и начинается с арендатора. Прежний индекс
-- (tenant_id, overdue) хранил все заявки, включая непросроченные.
DROP INDEX idx_requests_tenant_overdue;
CREATE INDEX idx_requests_tenant_overdue ON requests (tenant_id)
    WHERE (first_response_overdue OR resolution_overdue) AND NOT deleted;

-- Поиск фоновой проверки — по всем арендаторам сразу, поэтому индексы не начинаются с
-- арендатора, как и индекс захвата outbox. Они частичные: в них только заявки, которые ещё могут
-- просрочиться, и уже помеченные из них сразу выпадают. Условия совпадают с запросами
-- RequestRepository.markFirstResponseOverdue и markResolutionOverdue — менять только вместе.
--
-- CREATE INDEX блокирует запись в таблицу, пока индекс строится. На большой таблице индексы
-- строят заранее через CREATE INDEX CONCURRENTLY, вне транзакции миграции.
CREATE INDEX idx_requests_first_response_check ON requests (first_response_due_at)
    WHERE status = 'NEW' AND NOT deleted AND NOT first_response_overdue;
CREATE INDEX idx_requests_resolution_check ON requests (resolution_due_at)
    WHERE status IN ('NEW', 'CONTACTED', 'IN_PROGRESS') AND NOT deleted
        AND NOT resolution_overdue;
