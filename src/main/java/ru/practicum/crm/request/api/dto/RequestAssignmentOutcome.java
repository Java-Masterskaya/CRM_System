package ru.practicum.crm.request.api.dto;

public record RequestAssignmentOutcome(
        RequestAssignmentDto assignment,
        boolean changed
) {
}
