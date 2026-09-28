package ru.practicum.crm.user.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.user.domain.RoleEntity;

public interface RoleRepository extends Repository<RoleEntity, UUID> {

    RoleEntity save(RoleEntity role);

    Optional<RoleEntity> findById(UUID id);

    Optional<RoleEntity> findByTenantIdAndCode(UUID tenantId, String code);
}
