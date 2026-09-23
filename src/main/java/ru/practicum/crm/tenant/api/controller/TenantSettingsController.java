package ru.practicum.crm.tenant.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.tenant.api.dto.TenantSettingsDto;
import ru.practicum.crm.tenant.service.TenantSettingsService;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/admin/tenant/settings")
public class TenantSettingsController {

    private final TenantSettingsService service;

    @GetMapping
    public TenantSettingsDto getTenantSettings() {
        return service.getTenantSettings();
    }

    @PutMapping
    public TenantSettingsDto updateTenantSettings(
            @Valid
            @RequestBody
            TenantSettingsDto tenantSettingsDto) {
        return service.updateTenantSettings(tenantSettingsDto);
    }
}
