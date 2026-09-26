package ru.practicum.crm.tenant.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.tenant.domain.Tenant;

public interface TenantRepository extends Repository<Tenant, UUID> {
    Tenant save(Tenant tenant);

    Optional<Tenant> findById(UUID id);
}
