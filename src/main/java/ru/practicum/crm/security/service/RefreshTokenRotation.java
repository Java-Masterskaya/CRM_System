package ru.practicum.crm.security.service;

import java.util.UUID;

public record RefreshTokenRotation(
        UUID userId,
        UUID tenantId,
        String refreshToken
) {
}
