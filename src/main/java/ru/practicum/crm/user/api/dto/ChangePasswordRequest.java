package ru.practicum.crm.user.api.dto;

public record ChangePasswordRequest(
        String currentPassword,
        String newPassword
) {
}
