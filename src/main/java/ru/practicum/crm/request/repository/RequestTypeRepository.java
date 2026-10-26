package ru.practicum.crm.request.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.request.domain.RequestType;

/**
 * Доступ к справочнику типов заявок.
 *
 * <p>Как и в {@link RequestRepository}, интерфейс наследует маркерный {@link Repository} и
 * объявляет операции поимённо: метода, способного вернуть или изменить тип чужого арендатора,
 * здесь нет. Удаления нет вовсе — тип отключается, потому что на него ссылаются ранее
 * созданные заявки.
 */
public interface RequestTypeRepository extends Repository<RequestType, UUID> {

    <S extends RequestType> S save(S requestType);

    <S extends RequestType> S saveAndFlush(S requestType);

    Optional<RequestType> findByIdAndTenantId(UUID id, UUID tenantId);

    Page<RequestType> findByTenantId(UUID tenantId, Pageable pageable);

    Page<RequestType> findByTenantIdAndActiveTrue(UUID tenantId, Pageable pageable);

    boolean existsByTenantIdAndNameIgnoreCase(UUID tenantId, String name);
}
