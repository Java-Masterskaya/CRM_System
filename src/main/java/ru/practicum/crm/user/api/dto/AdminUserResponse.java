package ru.practicum.crm.user.api.dto;

import java.util.List;
import java.util.UUID;

/** Данные пользователя для администратора, без пароля и сведений о токенах. */
public record AdminUserResponse(
        UUID id,
        String email,
        String name,
        UserAccountStatus status,
        List<String> roles
) {
    public AdminUserResponse {
        roles = List.copyOf(roles.stream().distinct().sorted().toList());
    }
}
