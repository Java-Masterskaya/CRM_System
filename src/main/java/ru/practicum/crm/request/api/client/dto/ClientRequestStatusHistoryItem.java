package ru.practicum.crm.request.api.client.dto;

import java.time.Instant;
import ru.practicum.crm.request.domain.RequestStatus;

public record ClientRequestStatusHistoryItem(
        RequestStatus fromStatus,
        RequestStatus toStatus,
        Instant createdAt
) {
}
