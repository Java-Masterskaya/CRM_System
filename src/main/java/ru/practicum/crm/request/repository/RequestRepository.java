package ru.practicum.crm.request.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Доступ к заявкам.
 *
 * <p>Интерфейс наследует маркерный {@link Repository}, а не {@code JpaRepository}, и
 * перечисляет доступные операции поимённо. Иначе наружу вышли бы {@code findById},
 * {@code findAll}, {@code deleteById} и {@code count} — методы без арендатора, через которые
 * однажды утекут чужие данные. Каждое чтение здесь принимает арендатора и отсекает удалённые
 * заявки; централизованная фильтрация появится в T-015 (#16) и эти сигнатуры не отменит.
 */
public interface RequestRepository extends Repository<Request, UUID> {

    <S extends Request> S save(S request);

    Optional<Request> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    Page<Request> findByTenantIdAndDeletedFalse(UUID tenantId, Pageable pageable);

    Page<Request> findByTenantIdAndStatusAndDeletedFalse(UUID tenantId, RequestStatus status,
            Pageable pageable);

    boolean existsByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
