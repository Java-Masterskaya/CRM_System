package ru.practicum.crm.request.api.client.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.util.JsonDataParamsCopies;
import ru.practicum.crm.request.domain.RequestStatus;

public record ClientRequestCreatedResponse(
        UUID id,
        UUID typeId,
        String subject,
        String description,
        Map<String, Object> dataParams,
        RequestPriority priority,
        RequestStatus status,
        Instant desiredDueAt,
        Instant createdAt,
        Instant firstResponseDueAt,
        Instant resolutionDueAt
) {

    public ClientRequestCreatedResponse {
        dataParams = JsonDataParamsCopies.immutableCopy(dataParams);
    }

    @Override
    public Map<String, Object> dataParams() {
        return JsonDataParamsCopies.immutableCopy(dataParams);
    }
}
