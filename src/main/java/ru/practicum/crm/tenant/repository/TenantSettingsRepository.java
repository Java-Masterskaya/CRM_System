package ru.practicum.crm.tenant.repository;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.repository.Repository;
import ru.practicum.crm.tenant.domain.TenantSettings;

public interface TenantSettingsRepository extends Repository<TenantSettings, UUID> {
    Optional<TenantSettings> findByTenantId(UUID tenantId);

    TenantSettings save(TenantSettings settings);
}
