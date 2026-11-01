package ru.practicum.crm.security.service;

import java.util.UUID;

/** Владелец refresh-токена для блокировки пользователя перед ротацией. */
public record RefreshTokenSubject(UUID userId, UUID tenantId) {
}
