package ru.practicum.crm.tenant.api.provisioning;

import java.util.UUID;

public interface TenantProvisioning {

    TenantProvisioningResult create(String name, String slug);

    record TenantProvisioningResult(
            UUID tenantId,
            String name,
            String slug,
            boolean active
    ) {
    }
}
