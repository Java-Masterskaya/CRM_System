-- Нераспределенные заявки(T-053): без исполнителя, еще не завершены. Таких заявок немного
-- относительно всего реестра, поэтому индекс частичный - только они и начинаются с арендатора.

CREATE INDEX idx_requests_tenant_unassigned
    ON requests (tenant_id, created_at, id)
    WHERE assignee_id IS NULL
        AND NOT deleted
        AND status IN ('NEW', 'CONTACTED', 'IN_PROGRESS', 'ON_HOLD')
