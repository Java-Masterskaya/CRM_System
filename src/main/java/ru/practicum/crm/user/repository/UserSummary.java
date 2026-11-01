package ru.practicum.crm.user.repository;

import java.util.UUID;
import ru.practicum.crm.user.domain.UserStatus;

/** Проекция списка пользователей: пароль и его хэш из БД не выбираются. */
public record UserSummary(UUID id, String email, String name, UserStatus status) {
}
