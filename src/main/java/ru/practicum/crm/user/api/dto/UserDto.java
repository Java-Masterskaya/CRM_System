package ru.practicum.crm.user.api.dto;

import ru.practicum.crm.user.domain.UserStatus;

public record UserDto(
        String email,
        UserStatus status
) {
}
