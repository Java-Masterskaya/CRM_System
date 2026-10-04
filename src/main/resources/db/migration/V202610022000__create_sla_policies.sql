-- Политики сроков (T-055, ТЗ §6.4, SPEC §3.7): срок первого ответа и срок решения для пары
-- «тип заявки + приоритет» и одна политика по умолчанию на арендатора.

-- Пара «арендатор + тип заявки» объявляется уникальной, чтобы на неё мог сослаться составной
-- внешний ключ политики. id типа уникален сам по себе, поэтому существующие данные это
-- ограничение нарушить не могут. Команда блокирует справочник типов, пока строится индекс;
-- справочник маленький, для большой таблицы индекс строят заранее (см. миграцию комментариев).
ALTER TABLE request_types
    ADD CONSTRAINT request_types_tenant_id_id_unique UNIQUE (tenant_id, id);

CREATE TABLE sla_policies (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    -- Тип и приоритет заданы оба — политика для этой пары; оба пусты — политика по умолчанию.
    type_id UUID,
    priority VARCHAR(20),
    -- Сроки — в минутах рабочего времени: целые числа проще складывать и сравнивать, чем
    -- текстовые интервалы. В какие часы идёт рабочее время, определяет календарь (T-056).
    first_response_minutes INTEGER NOT NULL,
    resolution_minutes INTEGER NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    -- Тип заявки принадлежит тому же арендатору, что и политика. Для политики по умолчанию
    -- type_id пуст, и ключ не проверяется.
    CONSTRAINT sla_policies_type_fkey
        FOREIGN KEY (tenant_id, type_id) REFERENCES request_types (tenant_id, id),
    CONSTRAINT sla_policies_scope_check CHECK ((type_id IS NULL) = (priority IS NULL)),
    CONSTRAINT sla_policies_priority_check CHECK (
        priority IN ('LOW', 'NORMAL', 'HIGH', 'URGENT')
    ),
    CONSTRAINT sla_policies_positive_check CHECK (
        first_response_minutes > 0 AND resolution_minutes > 0
    ),
    CONSTRAINT sla_policies_order_check CHECK (first_response_minutes <= resolution_minutes)
);

COMMENT ON TABLE sla_policies IS 'Политики сроков: срок первого ответа и срок решения';
COMMENT ON COLUMN sla_policies.type_id IS 'Тип заявки; пусто у политики по умолчанию';
COMMENT ON COLUMN sla_policies.priority IS 'Приоритет: LOW, NORMAL, HIGH, URGENT; пусто у политики по умолчанию';
COMMENT ON COLUMN sla_policies.first_response_minutes IS 'Срок первого ответа в минутах рабочего времени';
COMMENT ON COLUMN sla_policies.resolution_minutes IS 'Срок решения в минутах рабочего времени';

-- На одну пару «тип + приоритет» — одна политика; на арендатора — одна политика по умолчанию.
-- Два частичных индекса, а не одно ограничение: в обычном ограничении уникальности пустые
-- значения считаются разными, и вторую политику по умолчанию оно бы пропустило. Первый индекс
-- заодно обслуживает список политик арендатора.
CREATE UNIQUE INDEX sla_policies_pair_unique
    ON sla_policies (tenant_id, type_id, priority) WHERE type_id IS NOT NULL;
CREATE UNIQUE INDEX sla_policies_default_unique
    ON sla_policies (tenant_id) WHERE type_id IS NULL;
