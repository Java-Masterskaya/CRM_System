package ru.practicum.crm.tenant.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.practicum.crm.tenant.model.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {

    boolean existsByIdAndActiveTrue(UUID id);
}
