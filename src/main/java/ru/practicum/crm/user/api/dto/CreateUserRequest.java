package ru.practicum.crm.user.api.dto;

public record CreateUserRequest(
        String email,
        String password
) {
}
