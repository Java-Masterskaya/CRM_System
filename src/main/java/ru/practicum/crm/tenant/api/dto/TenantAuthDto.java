package ru.practicum.crm.tenant.api.dto;

import java.util.UUID;

public record TenantAuthDto(
        UUID id,
        boolean active
) {
}
