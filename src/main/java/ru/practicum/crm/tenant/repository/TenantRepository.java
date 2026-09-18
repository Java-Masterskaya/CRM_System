package ru.practicum.crm.tenant.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.practicum.crm.tenant.model.Tenant;
import java.util.UUID;

public interface TenantRepository extends JpaRepository<Tenant, UUID> {
}
