package ru.practicum.crm.request.api.dto;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.RequestStatus;

public record RequestCardResponse(
        UUID id,
        String subject,
        String description,
        Map<String, Object> dataParams,
        RequestStatus status,
        RequestPriority priority,
        UUID typeId,
        UUID authorId,
        UUID assigneeId,
        Instant assignedAt,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        Instant desiredDueAt,
        Instant firstResponseDueAt,
        Instant resolutionDueAt,
        boolean firstResponseOverdue,
        boolean resolutionOverdue,
        boolean overdue,
        long version
) {

    public RequestCardResponse {
        dataParams = dataParams == null ? null : Map.copyOf(dataParams);
    }
}
