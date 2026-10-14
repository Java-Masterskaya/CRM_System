package ru.practicum.crm.user.api.dto;

public record ClientProfileDto(
        String name,
        String email,
        String phone
) {
}
