package ru.practicum.crm.tenant.service;

import ru.practicum.crm.tenant.api.dto.TenantSettingsDTO;

public interface TenantSettingsService {
    TenantSettingsDTO getTenantSettings();

    TenantSettingsDTO updateTenantSettings(TenantSettingsDTO tenantSettingsDTO);
}
