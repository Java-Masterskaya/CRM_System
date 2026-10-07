package ru.practicum.crm.sla.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.sla.domain.WorkingHours;

/**
 * Строки рабочего календаря. Как и в остальных репозиториях проекта, операции перечислены
 * поимённо, а чтение — только с арендатором.
 */
public interface WorkingHoursRepository extends Repository<WorkingHours, UUID> {

    List<WorkingHours> findByTenantId(UUID tenantId);

    boolean existsByTenantId(UUID tenantId);

    /**
     * Удаляет календарь арендатора одним запросом, сразу. Удаление по одной сущности не годится:
     * Hibernate выполняет удаления после вставок, и вставка того же дня новой недели нарушила бы
     * уникальность раньше, чем удалится старая строка.
     */
    @Modifying
    @Query("DELETE FROM WorkingHours h WHERE h.tenantId = :tenantId")
    int deleteByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Сохраняет дни и сразу отправляет их в базу: нарушение уникальности от одновременной
     * замены календаря должно всплыть здесь, а не при фиксации транзакции, иначе сервис не
     * сможет превратить его в понятную ошибку.
     */
    <S extends WorkingHours> List<S> saveAllAndFlush(Iterable<S> days);
}
