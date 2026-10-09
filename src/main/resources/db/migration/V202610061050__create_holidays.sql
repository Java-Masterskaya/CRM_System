-- Справочник нерабочих дней арендатора (T-057, SPEC §3.7): даты, которые не следуют недельному
-- рабочему календарю (T-056). Дата — местная для арендатора, как и часы календаря.
--
-- Запись без часов — нерабочий день: праздник или корпоративный выходной, даже если по
-- календарю день будний. Запись с часами — рабочий день с этими часами: перенос, когда обычно
-- выходной день становится рабочим (так же задаётся и сокращённый рабочий день).
CREATE TABLE holidays (
    id UUID PRIMARY KEY,
    tenant_id UUID NOT NULL REFERENCES tenants (id),
    holiday_date DATE NOT NULL,
    description VARCHAR(255) NOT NULL CONSTRAINT holidays_description_check CHECK (
        btrim(description) <> ''
    ),
    -- Оба пусты — день нерабочий; оба заданы — рабочий день с этими часами. Начало входит в
    -- рабочее время, конец — нет, как в календаре.
    start_time TIME,
    end_time TIME,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT holidays_hours_check CHECK ((start_time IS NULL) = (end_time IS NULL)),
    CONSTRAINT holidays_interval_check CHECK (start_time < end_time),
    -- Одна запись на дату: дата либо нерабочая, либо рабочая со своими часами. Индекс
    -- начинается с арендатора и обслуживает и справочник по порядку дат, и загрузку календаря.
    CONSTRAINT holidays_date_unique UNIQUE (tenant_id, holiday_date)
);

COMMENT ON TABLE holidays IS 'Справочник нерабочих дней арендатора: праздники, выходные и переносы';
COMMENT ON COLUMN holidays.holiday_date IS 'Дата, местная для арендатора';
COMMENT ON COLUMN holidays.description IS 'Что это за день, например «Новый год» или «Перенос с 3 января»';
COMMENT ON COLUMN holidays.start_time IS 'Начало рабочего дня; пусто — день нерабочий';
COMMENT ON COLUMN holidays.end_time IS 'Конец рабочего дня, не включается; пусто — день нерабочий';
