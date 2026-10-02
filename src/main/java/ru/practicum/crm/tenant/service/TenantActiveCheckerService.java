package ru.practicum.crm.tenant.service;

import java.util.UUID;
import org.springframework.stereotype.Service;
import ru.practicum.crm.tenant.api.TenantActiveChecker;
import ru.practicum.crm.tenant.repository.TenantRepository;

@Service
public class TenantActiveCheckerService implements TenantActiveChecker {

    private final TenantRepository tenantRepository;

    public TenantActiveCheckerService(TenantRepository tenantRepository) {
        this.tenantRepository = tenantRepository;
    }

    @Override
    public boolean isActive(UUID tenantId) {
        return tenantRepository.existsByIdAndActiveTrue(tenantId);
    }
}
