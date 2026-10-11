package ru.practicum.crm.user.api.dto;

import java.util.UUID;

public record AuthenticatedUserDto(
        UUID id,
        UUID tenantId
) {
}
