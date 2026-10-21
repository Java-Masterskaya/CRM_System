package ru.practicum.crm.request.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import ru.practicum.crm.request.domain.RequestStatus;

public record RequestStatusHistoryItem(
        UUID id,
        RequestStatus fromStatus,
        RequestStatus toStatus,
        UUID authorId,
        String comment,
        OffsetDateTime createdAt
) {
}
