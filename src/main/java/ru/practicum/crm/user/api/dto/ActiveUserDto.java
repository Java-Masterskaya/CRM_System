package ru.practicum.crm.user.api.dto;

import java.util.UUID;

public record ActiveUserDto(UUID id, String email) {
}
