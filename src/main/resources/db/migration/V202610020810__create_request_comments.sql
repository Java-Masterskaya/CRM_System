-- Комментарии заявки (T-074, ТЗ §4.2): текст, автор и признак «внутренний / видимый клиенту».

-- Пара «арендатор + заявка» объявляется уникальной, чтобы на неё мог сослаться составной внешний
-- ключ комментария. id заявки уникален сам по себе, поэтому существующие данные это ограничение
-- нарушить не могут. Команда блокирует таблицу заявок, пока строится индекс; на большой таблице
-- его стоит заранее построить отдельно (CREATE UNIQUE INDEX CONCURRENTLY) и подключить через
-- ADD CONSTRAINT ... USING INDEX.
ALTER TABLE requests ADD CONSTRAINT requests_tenant_id_id_unique UNIQUE (tenant_id, id);

CREATE TABLE request_comments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    request_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES users (id),
    text TEXT NOT NULL CONSTRAINT request_comments_text_length_check CHECK (
        char_length(text) BETWEEN 1 AND 4000
    ),
    -- По умолчанию комментарий внутренний: если признак не указали, клиент не увидит лишнего.
    visible_to_client BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Комментарий принадлежит тому же арендатору, что и заявка: привязать его к заявке другого
    -- арендатора база не даст.
    CONSTRAINT request_comments_request_fkey
        FOREIGN KEY (tenant_id, request_id) REFERENCES requests (tenant_id, id)
);

COMMENT ON TABLE request_comments IS 'Комментарии к заявкам с признаком видимости клиенту';
COMMENT ON COLUMN request_comments.author_id IS 'Пользователь, написавший комментарий';
COMMENT ON COLUMN request_comments.text IS 'Текст комментария, от 1 до 4000 символов';
COMMENT ON COLUMN request_comments.visible_to_client IS 'TRUE — комментарий виден клиенту, FALSE — только команде';

-- Под выборку комментариев одной заявки по времени; tenant_id первым, как в остальных индексах
-- арендаторских таблиц.
CREATE INDEX idx_request_comments_tenant_request_created_at
    ON request_comments (tenant_id, request_id, created_at);
