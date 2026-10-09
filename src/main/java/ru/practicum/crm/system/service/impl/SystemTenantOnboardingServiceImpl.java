package ru.practicum.crm.system.service.impl;

import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingRequest;
import ru.practicum.crm.system.api.dto.SystemTenantOnboardingResponse;
import ru.practicum.crm.system.service.SystemTenantOnboardingService;
import ru.practicum.crm.tenant.api.provisioning.TenantProvisioning;
import ru.practicum.crm.user.api.provisioning.AdminProvisioning;

@Service
@RequiredArgsConstructor
public class SystemTenantOnboardingServiceImpl
        implements SystemTenantOnboardingService {

    private final TenantProvisioning tenantProvisioning;
    private final AdminProvisioning adminProvisioning;

    @Override
    @Transactional
    public SystemTenantOnboardingResponse onboard(
            SystemTenantOnboardingRequest request) {

        TenantProvisioning.TenantProvisioningResult tenant =
                tenantProvisioning.create(
                        request.name(),
                        request.slug()
                );

        adminProvisioning.createInitialAdmin(
                tenant.tenantId(),
                request.adminEmail(),
                request.adminPassword()
        );

        return new SystemTenantOnboardingResponse(
                tenant.tenantId(),
                tenant.name(),
                tenant.slug(),
                tenant.active()
        );
    }
}
