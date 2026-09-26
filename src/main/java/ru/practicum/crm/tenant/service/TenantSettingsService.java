package ru.practicum.crm.tenant.service;

import ru.practicum.crm.tenant.api.dto.TenantSettingsDto;

public interface TenantSettingsService {
    TenantSettingsDto getTenantSettings();

    TenantSettingsDto updateTenantSettings(TenantSettingsDto tenantSettingsDto);
}
