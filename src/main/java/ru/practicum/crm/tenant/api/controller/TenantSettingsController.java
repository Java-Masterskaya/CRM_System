package ru.practicum.crm.tenant.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDTO;
import ru.practicum.crm.tenant.service.TenantSettingsService;

@RequiredArgsConstructor
@RestController("/api/v1/tenants/settings")
public class TenantSettingsController {

    private final TenantSettingsService service;

    @GetMapping
    public TenantSettingsDTO getTenantSettings() {
        return service.getTenantSettings();
    }

    @PutMapping
    public TenantSettingsDTO updateTenantSettings(@Valid @RequestBody TenantSettingsDTO tenantSettingsDTO) {
        return service.updateTenantSettings(tenantSettingsDTO);
    }
}
