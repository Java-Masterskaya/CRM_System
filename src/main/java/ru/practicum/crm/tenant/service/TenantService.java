package ru.practicum.crm.tenant.service;

import ru.practicum.crm.tenant.api.dto.TenantDto;

public interface TenantService {
    TenantDto createTenant(TenantDto request);
}
