-- Эскалация только открытых заявок (T-061, ревью #198): выборки эскалации проверяют статус так же,
-- как пометка просрочки (T-060). Заявку закрыли между пометкой и эскалацией — например, проход
-- прервался и письма досталось писать следующему, — письма по ней не будет.
--
-- Частичные индексы выборок пересоздаются с тем же условием, что у запросов
-- RequestRepository.lockFirstResponseToEscalate и lockResolutionToEscalate, — менять только вместе.
-- Отдельной миграцией, а не правкой V202610151010: та уже применялась при проверке.
DROP INDEX idx_requests_first_response_escalation;

CREATE INDEX idx_requests_first_response_escalation
    ON requests (first_response_due_at)
    WHERE status = 'NEW' AND first_response_overdue AND NOT first_response_escalated
      AND NOT deleted;

DROP INDEX idx_requests_resolution_escalation;

CREATE INDEX idx_requests_resolution_escalation
    ON requests (resolution_due_at)
    WHERE status IN ('NEW', 'CONTACTED', 'IN_PROGRESS') AND resolution_overdue
      AND NOT resolution_escalated AND NOT deleted;
