-- Вложения заявки (T-078, ТЗ §4.2 и §6.6): ссылка на объект внешнего медиа-сервиса, привязанная
-- к заявке. Сам файл CRM не хранит — здесь только то, что нужно, чтобы показать вложение в списке
-- и найти объект в медиа-сервисе.
CREATE TABLE request_attachments (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    request_id UUID NOT NULL,
    author_id UUID NOT NULL REFERENCES users (id),
    -- Идентификатор объекта в медиа-сервисе, а не готовый адрес: адрес сервиса берётся из
    -- конфигурации. Строка, потому что формат идентификатора задаёт медиа-сервис.
    object_id VARCHAR(255) NOT NULL CONSTRAINT request_attachments_object_id_check CHECK (
        btrim(object_id) <> ''
    ),
    file_name VARCHAR(255) NOT NULL CONSTRAINT request_attachments_file_name_check CHECK (
        btrim(file_name) <> ''
    ),
    size_bytes BIGINT NOT NULL CONSTRAINT request_attachments_size_check CHECK (size_bytes >= 0),
    content_type VARCHAR(255) NOT NULL CONSTRAINT request_attachments_content_type_check CHECK (
        btrim(content_type) <> ''
    ),
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Вложение принадлежит тому же арендатору, что и заявка: привязать его к заявке другого
    -- арендатора база не даст. Уникальность (tenant_id, id) у requests добавлена в T-074.
    CONSTRAINT request_attachments_request_fkey
        FOREIGN KEY (tenant_id, request_id) REFERENCES requests (tenant_id, id),
    -- Один объект привязывается к заявке один раз. Этот же индекс обслуживает выборку вложений
    -- заявки: он начинается с арендатора и заявки, отдельный индекс под неё не нужен.
    CONSTRAINT request_attachments_object_unique UNIQUE (tenant_id, request_id, object_id)
);

COMMENT ON TABLE request_attachments IS 'Вложения заявок: ссылки на объекты медиа-сервиса';
COMMENT ON COLUMN request_attachments.author_id IS 'Пользователь, приложивший файл';
COMMENT ON COLUMN request_attachments.object_id IS 'Идентификатор объекта в медиа-сервисе';
COMMENT ON COLUMN request_attachments.file_name IS 'Имя файла, как его показывают пользователю';
COMMENT ON COLUMN request_attachments.size_bytes IS 'Размер файла в байтах';
COMMENT ON COLUMN request_attachments.content_type IS 'Тип содержимого, например application/pdf';
