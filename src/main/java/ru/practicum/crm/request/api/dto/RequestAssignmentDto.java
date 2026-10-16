package ru.practicum.crm.request.api.dto;

import java.time.Instant;
import java.util.UUID;

public record RequestAssignmentDto(UUID requestId, UUID authorId, String subject,
                                   UUID assigneeId, Instant assignedAt, long version) {
}
