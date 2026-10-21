package ru.practicum.crm.request.api.client.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.util.JsonDataParamsCopies;
import ru.practicum.crm.request.domain.RequestStatus;

public record ClientRequestCardResponse(
        UUID id,
        UUID typeId,
        String subject,
        String description,
        Map<String, Object> dataParams,
        RequestPriority priority,
        RequestStatus status,
        Instant createdAt,
        Instant desiredDueAt,
        Instant firstResponseDueAt,
        Instant resolutionDueAt,
        List<ClientRequestStatusHistoryItem> statusHistory
) {

    public ClientRequestCardResponse {
        dataParams = JsonDataParamsCopies.immutableCopy(dataParams);
        statusHistory = List.copyOf(statusHistory);
    }

    @Override
    public Map<String, Object> dataParams() {
        return JsonDataParamsCopies.immutableCopy(dataParams);
    }
}
