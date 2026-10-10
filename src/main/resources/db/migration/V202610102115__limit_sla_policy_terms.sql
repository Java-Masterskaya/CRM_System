-- Потолок сроков политики (доработка T-058 по ревью #181): не больше года в минутах,
-- 365 × 24 × 60 = 525 600. Без потолка срок в миллионы минут при календаре с минутой рабочего
-- времени в неделю считался бы очень долго, а получившаяся дата могла бы не поместиться в
-- TIMESTAMPTZ. Тот же предел проверяет SlaPolicyService; ограничение базы — страховка от записи
-- в обход сервиса.

-- Строки, которые уже превышают потолок, урезаются до него, иначе ограничение не добавится.
-- LEAST сохраняет порядок сроков: срок первого ответа по-прежнему не больше срока решения.
UPDATE sla_policies
SET first_response_minutes = LEAST(first_response_minutes, 525600),
    resolution_minutes = LEAST(resolution_minutes, 525600),
    updated_at = now()
WHERE first_response_minutes > 525600 OR resolution_minutes > 525600;

-- Добавление ограничения проверяет все строки под блокировкой таблицы; политик у арендатора
-- единицы, так что это быстро.
ALTER TABLE sla_policies
    ADD CONSTRAINT sla_policies_limit_check CHECK (
        first_response_minutes <= 525600 AND resolution_minutes <= 525600
    );
