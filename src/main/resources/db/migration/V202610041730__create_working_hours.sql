-- Рабочий календарь арендатора (T-056, ТЗ §6.4, SPEC §3.7): рабочие часы по дням недели.
-- Один интервал на день; дня нет в таблице — день выходной. Время — местное для арендатора:
-- часовой пояс берётся из его настроек (tenant_settings.timezone, T-017), поэтому переход на
-- летнее время не сдвигает рабочий день на час.
CREATE TABLE working_hours (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    -- Имя дня недели из java.time.DayOfWeek; самое длинное — WEDNESDAY, 9 символов.
    day_of_week VARCHAR(9) NOT NULL CONSTRAINT working_hours_day_check CHECK (
        day_of_week IN (
            'MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'
        )
    ),
    -- Начало входит в рабочее время, конец — нет: при 09:00–18:00 момент 18:00 уже нерабочий.
    start_time TIME NOT NULL,
    end_time TIME NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT working_hours_interval_check CHECK (start_time < end_time),
    -- Один интервал на день. Индекс начинается с арендатора и заодно обслуживает чтение
    -- календаря арендатора.
    CONSTRAINT working_hours_day_unique UNIQUE (tenant_id, day_of_week)
);

COMMENT ON TABLE working_hours IS 'Рабочий календарь арендатора: рабочие часы по дням недели';
COMMENT ON COLUMN working_hours.day_of_week IS 'День недели: MONDAY … SUNDAY';
COMMENT ON COLUMN working_hours.start_time IS 'Начало рабочего дня, местное время арендатора';
COMMENT ON COLUMN working_hours.end_time IS 'Конец рабочего дня, не включается; местное время арендатора';

-- Календарь по умолчанию — понедельник–пятница, 09:00–18:00 — для арендаторов, которые уже
-- есть. Новых наполняет TenantService в транзакции создания (TenantCalendarSeeder), как роли
-- и права в T-021. Синтетический арендатор System с нулевым UUID — не настоящий арендатор
-- (деактивирован в V202610021000), календарь ему не нужен.
INSERT INTO working_hours (id, tenant_id, day_of_week, start_time, end_time, created_at,
                           updated_at)
SELECT gen_random_uuid(), t.id, d.day_of_week, TIME '09:00', TIME '18:00', now(), now()
FROM tenants t
CROSS JOIN (
    VALUES ('MONDAY'), ('TUESDAY'), ('WEDNESDAY'), ('THURSDAY'), ('FRIDAY')
) AS d (day_of_week)
WHERE t.id <> '00000000-0000-0000-0000-000000000000';
