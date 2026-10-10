package ru.practicum.crm.sla.api;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Расчёт сроков заявки (T-058): политика сроков для её типа и приоритета (T-055) и сложение
 * рабочих минут по календарю арендатора (T-056) со справочником нерабочих дней (T-057).
 */
public interface RequestDeadlineCalculator {

    /**
     * Сроки заявки, отсчитанные от момента её создания.
     *
     * <p>Не бросает исключений из-за настроек арендатора: если сроки посчитать нельзя — нет
     * политики, нет настроек арендатора или в календаре нет рабочего времени, — возвращает
     * пусто и пишет причину в лог. Заявка при этом должна создаваться без сроков.
     *
     * @param typeId тип заявки; у заявки без типа применяется политика по умолчанию
     * @param createdAt момент создания заявки
     */
    Optional<RequestDeadlines> calculate(UUID tenantId, UUID typeId, RequestPriority priority,
            Instant createdAt);
}
