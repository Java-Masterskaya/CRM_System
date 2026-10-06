package ru.practicum.crm.system.api.dto;

import java.util.UUID;

public record SystemTenantOnboardingResponse(
        UUID tenantId,
        String name,
        String slug,
        boolean active
) {
}
