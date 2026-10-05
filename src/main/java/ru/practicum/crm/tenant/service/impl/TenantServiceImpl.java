package ru.practicum.crm.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.dto.TenantAuthDto;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.api.mapper.TenantMapper;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.service.TenantService;

@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {
    private final TenantRepository tenantRepository;
    private final TenantMapper mapper;
    private final TenantAccessSeeder tenantAccessSeeder;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public TenantDto createTenant(TenantDto request) {
        Tenant tenant = new Tenant(request.name(), request.slug());
        TenantSettings settings =
                TenantSettings.createDefaults(tenant);

        tenant.initializeSettings(settings);
        tenantRepository.save(tenant);
        entityManager.flush();
        tenantAccessSeeder.seedDefaults(tenant.getId());

        return mapper.toDto(tenant);
    }

    @Override
    public TenantAuthDto findActiveBySlug(String slug) {
        Tenant tenant = tenantRepository.findBySlugAndActiveTrue(slug).orElseThrow(
                () -> new ApiException(ErrorCode.INVALID_CREDENTIALS,
                        ErrorCode.INVALID_CREDENTIALS.getDefaultDetail())
        );
        return mapper.toAuthDto(tenant);
    }
}
