package ru.practicum.crm.security.service;

import java.util.UUID;

public interface RefreshTokenService {
    String create(UUID userId, UUID tenantId);
}
