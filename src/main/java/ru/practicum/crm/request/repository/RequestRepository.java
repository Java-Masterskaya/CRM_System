package ru.practicum.crm.request.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Доступ к заявкам.
 *
 * <p>Каждый метод чтения принимает идентификатор арендатора и отсекает удалённые заявки:
 * пока централизованная фильтрация по арендатору (T-015) не сделана, изоляция держится
 * на сигнатурах — метода, читающего заявку без арендатора, здесь нет намеренно.
 */
public interface RequestRepository extends JpaRepository<Request, UUID> {

    Optional<Request> findByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);

    Page<Request> findByTenantIdAndDeletedFalse(UUID tenantId, Pageable pageable);

    Page<Request> findByTenantIdAndStatusAndDeletedFalse(UUID tenantId, RequestStatus status,
            Pageable pageable);

    boolean existsByIdAndTenantIdAndDeletedFalse(UUID id, UUID tenantId);
}
