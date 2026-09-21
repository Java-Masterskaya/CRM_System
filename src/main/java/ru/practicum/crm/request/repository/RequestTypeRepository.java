package ru.practicum.crm.request.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
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

    Optional<RequestType> findByIdAndTenantId(UUID id, UUID tenantId);

    List<RequestType> findByTenantIdOrderByNameAsc(UUID tenantId);

    List<RequestType> findByTenantIdAndActiveTrueOrderByNameAsc(UUID tenantId);

    boolean existsByTenantIdAndName(UUID tenantId, String name);
}
