package ru.practicum.crm.sla.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.sla.domain.Holiday;

/**
 * Справочник нерабочих дней. Как и в остальных репозиториях проекта, операции перечислены
 * поимённо, а чтение — только с арендатором.
 */
public interface HolidayRepository extends Repository<Holiday, UUID> {

    /**
     * Сохраняет запись и сразу отправляет её в базу: повтор даты от одновременного запроса
     * должен всплыть здесь, а не при фиксации транзакции, иначе сервис не сможет превратить его
     * в понятную ошибку.
     */
    Holiday saveAndFlush(Holiday holiday);

    void delete(Holiday holiday);

    boolean existsByTenantIdAndDate(UUID tenantId, LocalDate date);

    Optional<Holiday> findByIdAndTenantId(UUID id, UUID tenantId);

    /** Все даты арендатора — для календаря; их немного, десятки в год. */
    List<Holiday> findByTenantId(UUID tenantId);

    /** Страница справочника по порядку дат; дата у арендатора уникальна, порядок однозначен. */
    Page<Holiday> findByTenantIdOrderByDateAsc(UUID tenantId, Pageable pageable);
}
