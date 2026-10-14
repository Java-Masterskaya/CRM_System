package ru.practicum.crm.system.service;

import ru.practicum.crm.system.api.dto.SystemTenantOnboardingRequest;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingResponse;

public interface SystemTenantOnboardingService {

    SystemTenantOnboardingResponse onboard(SystemTenantOnboardingRequest request);
}
