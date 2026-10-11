package ru.practicum.crm.system.api.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingRequest;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingResponse;
import ru.practicum.crm.system.service.SystemTenantOnboardingService;

@RequiredArgsConstructor
@RestController
@RequestMapping("/system/tenants")
public class SystemTenantController {

    private final SystemTenantOnboardingService service;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SystemTenantOnboardingResponse onboard(
            @Valid @RequestBody SystemTenantOnboardingRequest request) {
        return service.onboard(request);
    }
}
