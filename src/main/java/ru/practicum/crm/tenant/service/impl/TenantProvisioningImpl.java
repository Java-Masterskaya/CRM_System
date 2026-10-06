package ru.practicum.crm.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.provisioning.TenantProvisioning;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;

@Service
@RequiredArgsConstructor
public class TenantProvisioningImpl implements TenantProvisioning {

    private final TenantRepository tenantRepository;
    private final TenantAccessSeeder tenantAccessSeeder;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public TenantProvisioningResult create(String name, String slug) {
        if (tenantRepository.existsBySlug(slug)) {
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

        return new TenantProvisioningResult(
                tenant.getId(),
                tenant.getName(),
                tenant.getSlug(),
                tenant.isActive()
        );
    }
}
