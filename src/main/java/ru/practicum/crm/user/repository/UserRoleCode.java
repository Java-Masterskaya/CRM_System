package ru.practicum.crm.user.repository;

import java.util.UUID;

/** Код назначенной роли для пакетного заполнения страницы пользователей. */
public record UserRoleCode(UUID userId, String code) {
}
