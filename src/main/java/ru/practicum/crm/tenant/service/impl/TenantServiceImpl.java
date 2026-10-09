package ru.practicum.crm.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.api.mapper.TenantMapper;
import ru.practicum.crm.tenant.api.provisioning.TenantProvisioning;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;
import ru.practicum.crm.tenant.api.seeding.TenantCalendarSeeder;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.service.TenantService;

@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService, TenantProvisioning {
    private final TenantRepository tenantRepository;
    private final TenantMapper mapper;
    private final TenantAccessSeeder tenantAccessSeeder;
    private final TenantCalendarSeeder tenantCalendarSeeder;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public TenantDto createTenant(TenantDto request) {
        Tenant tenant = createTenantInternal(request.name(), null);
        return mapper.toDto(tenant);
    }

    @Override
    @Transactional
    public TenantProvisioningResult create(String name, String slug) {
        Tenant tenant = createTenantInternal(name, slug);
        return new TenantProvisioningResult(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.isActive()
        );
    }

    private Tenant createTenantInternal(String name, String slug) {
        if (slug != null && tenantRepository.existsBySlug(slug)) {
            throw new ApiException(
                    ErrorCode.ALREADY_EXISTS,
                    "Арендатор с slug '" + slug + "' уже существует."
            );
        }

        Tenant tenant = new Tenant(name, slug);
        TenantSettings settings =
                TenantSettings.createDefaults(tenant);

        tenant.initializeSettings(settings);
        tenantRepository.save(tenant);
        entityManager.flush();
        tenantAccessSeeder.seedDefaults(tenant.getId());
        tenantCalendarSeeder.seedDefaults(tenant.getId());

        return tenant;
    }
}
