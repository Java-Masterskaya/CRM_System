package ru.practicum.crm.tenant.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.tenant.api.dto.TenantDto;
import ru.practicum.crm.tenant.api.mapper.TenantMapper;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.domain.TenantSettings;
import ru.practicum.crm.tenant.repository.TenantRepository;
import ru.practicum.crm.tenant.repository.TenantSettingsRepository;
import ru.practicum.crm.tenant.service.TenantService;

@Service
@RequiredArgsConstructor
public class TenantServiceImpl implements TenantService {
    private final TenantRepository tenantRepository;
    private final TenantSettingsRepository tenantSettingsRepository;
    private final TenantMapper mapper;

    @Override
    @Transactional
    public TenantDto createTenant(TenantDto request) {
        Tenant tenant = tenantRepository.save(new Tenant(request.name()));

        TenantSettings settings =
                TenantSettings.createDefaults(tenant.getId());

        tenantSettingsRepository.save(settings);
        return mapper.toDto(tenant);
    }
}
