package ru.practicum.crm.tenant.api;

import ru.practicum.crm.tenant.api.dto.TenantAuthDto;
import ru.practicum.crm.tenant.api.dto.TenantDto;

public interface TenantService {
    TenantDto createTenant(TenantDto request);

    TenantAuthDto findActiveBySlug(String slug);
}
