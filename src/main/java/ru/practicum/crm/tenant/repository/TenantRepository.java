package ru.practicum.crm.tenant.repository;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import ru.practicum.crm.tenant.domain.Tenant;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
