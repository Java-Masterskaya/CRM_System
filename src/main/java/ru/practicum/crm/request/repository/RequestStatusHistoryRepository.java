package ru.practicum.crm.request.repository;

import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.request.domain.RequestStatusChange;

/**
 * История статусов заявок. Операций изменения и удаления здесь нет: история только пополняется.
 * Чтение для карточек заявки появится вместе с ними (T-038, T-041).
 */
public interface RequestStatusHistoryRepository extends Repository<RequestStatusChange, UUID> {

    <S extends RequestStatusChange> S save(S change);
}
