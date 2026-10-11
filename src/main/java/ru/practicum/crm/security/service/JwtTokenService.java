package ru.practicum.crm.security.service;

import java.util.Collection;
import java.util.UUID;

public interface JwtTokenService {
    String createAccessToken(UUID userId, UUID tenantId, Collection<String> permissions);
}
