package ru.practicum.crm.security.repository;

import java.util.UUID;

/** Владелец без загрузки изменяемого состояния токена в контекст JPA. */
public interface RefreshTokenOwner {
    UUID getUserId();

    UUID getTenantId();
}
