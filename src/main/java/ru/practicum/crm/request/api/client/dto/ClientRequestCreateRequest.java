package ru.practicum.crm.request.api.client.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.util.JsonDataParamsCopies;

public record ClientRequestCreateRequest(
        @NotNull
        UUID typeId,

        @NotBlank
        @Size(max = 255)
        String subject,

        @NotBlank
        @Size(max = 10_000)
        String description,

        Map<String, Object> dataParams,

        RequestPriority priority,

        Instant desiredDueAt
) {
    public ClientRequestCreateRequest {
        dataParams = JsonDataParamsCopies.immutableCopy(dataParams);
    }

    @Override
    public Map<String, Object> dataParams() {
        return JsonDataParamsCopies.immutableCopy(dataParams);
    }
}
