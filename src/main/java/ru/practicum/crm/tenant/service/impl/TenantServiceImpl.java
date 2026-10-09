package ru.practicum.crm.tenant.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.tenant.api.TenantLookup;
import ru.practicum.crm.tenant.api.TenantReference;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.api.mapper.TenantMapper;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;
import ru.practicum.crm.tenant.api.seeding.TenantCalendarSeeder;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.service.TenantService;

@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService, TenantLookup {
    private final TenantRepository tenantRepository;
    private final TenantMapper mapper;
    private final TenantAccessSeeder tenantAccessSeeder;
    private final TenantCalendarSeeder tenantCalendarSeeder;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public TenantDto createTenant(TenantDto request) {
        Tenant tenant = new Tenant(request.name(), request.slug(), true);
        TenantSettings settings =
                TenantSettings.createDefaults(tenant);

        tenant.initializeSettings(settings);
        tenantRepository.save(tenant);
        entityManager.flush();
        tenantAccessSeeder.seedDefaults(tenant.getId());
        tenantCalendarSeeder.seedDefaults(tenant.getId());

        return mapper.toDto(tenant);
    }

    @Override
    public Optional<TenantReference> findActiveBySlug(String slug) {
        return tenantRepository.findBySlugAndActiveTrue(slug)
                .map(tenant -> new TenantReference(tenant.getId()));
    }
}
