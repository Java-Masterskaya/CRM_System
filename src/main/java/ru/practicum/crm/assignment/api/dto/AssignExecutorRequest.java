package ru.practicum.crm.assignment.api.dto;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AssignExecutorRequest(UUID assigneeId, @NotNull Long version) {
}
