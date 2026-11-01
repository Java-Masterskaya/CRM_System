package ru.practicum.crm.user.api.dto;

import jakarta.validation.constraints.NotNull;

public record AdminUserStatusUpdateRequest(
        @NotNull
        UserAccountStatus status
) {
}
