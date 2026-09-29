-- Индексы для метрики возраста самого давнего недоставленного события (#154). Запрос
-- min(created_at) по одному состоянию берёт первую запись такого индекса, а не перебирает все
-- события в состоянии: без индекса запрос дорожает вместе с очередью — как раз при заторе,
-- когда метрика нужнее всего.
--
-- Частичные, по одному на состояние, а не общий (status, created_at): общий подходит и запросу
-- захвата порции (status IN ('NEW', 'IN_PROGRESS') ... ORDER BY next_attempt_at), и планировщик
-- мог выбрать его вместо idx_outbox_events_status_next_attempt. Тогда захват перестал бы
-- отсекать события, ждущие повтора, и при заторе перечитывал бы их все на каждом проходе.
-- Частичный индекс с условием на одно состояние запросу захвата не подходит: из «NEW или
-- IN_PROGRESS» не следует ни «NEW», ни «IN_PROGRESS».
--
-- Без tenant_id: метрика считается по всей очереди сразу, как и захват порции.
CREATE INDEX idx_outbox_events_new_created_at
    ON outbox_events (created_at) WHERE status = 'NEW';

CREATE INDEX idx_outbox_events_in_progress_created_at
    ON outbox_events (created_at) WHERE status = 'IN_PROGRESS';
