package ru.practicum.crm.tenant.service;

import org.springframework.security.access.prepost.PreAuthorize;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDto;

/**
 * Настройки текущего арендатора. Права проверяются здесь, на методах сервиса (T-030): правило
 * по адресу только пропускает запрос с токеном, а какое право нужно — решает операция.
 */
public interface TenantSettingsService {

    @PreAuthorize("hasAuthority('TENANT_SETTINGS_READ')")
    TenantSettingsDto getTenantSettings();

    @PreAuthorize("hasAuthority('TENANT_SETTINGS_MANAGE')")
    TenantSettingsDto updateTenantSettings(TenantSettingsDto tenantSettingsDto);
}
