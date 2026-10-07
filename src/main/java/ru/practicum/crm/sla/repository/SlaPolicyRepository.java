package ru.practicum.crm.sla.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.sla.domain.SlaPolicy;

/**
 * Политики сроков. Как и в остальных репозиториях проекта, операции перечислены поимённо, а
 * чтение — только с арендатором: политику другого арендатора найти нельзя.
 */
public interface SlaPolicyRepository extends Repository<SlaPolicy, UUID> {

    /**
     * Сохраняет политику и сразу отправляет запрос в базу: нарушение ограничения (чужой тип
     * заявки, повтор пары) должно всплыть здесь, а не при фиксации транзакции, иначе сервис не
     * сможет превратить его в понятную ошибку.
     */
    SlaPolicy saveAndFlush(SlaPolicy policy);

    Optional<SlaPolicy> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Политика для пары «тип заявки + приоритет». */
    Optional<SlaPolicy> findByTenantIdAndTypeIdAndPriority(UUID tenantId, UUID typeId,
            RequestPriority priority);

    /** Политика по умолчанию — та, у которой тип не задан; у арендатора она одна. */
    Optional<SlaPolicy> findByTenantIdAndTypeIdIsNull(UUID tenantId);

    /**
     * Страница политик арендатора в порядке создания. {@code id} — последний ключ сортировки,
     * поэтому порядок одинаков на всех страницах.
     */
    Page<SlaPolicy> findByTenantIdOrderByCreatedAtAscIdAsc(UUID tenantId, Pageable pageable);
}
