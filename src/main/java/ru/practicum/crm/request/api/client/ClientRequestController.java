package ru.practicum.crm.request.api.client;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.request.api.client.dto.ClientRequestCreateRequest;
import ru.practicum.crm.request.api.client.dto.ClientRequestCreatedResponse;
import ru.practicum.crm.request.api.dto.RequestStatusResponse;
import ru.practicum.crm.request.service.ClientRequestService;
import ru.practicum.crm.request.service.RequestStatusService;

@RestController
@RequestMapping("/client/requests")
@RequiredArgsConstructor
public class ClientRequestController {

    private final ClientRequestService clientRequestService;
    private final RequestStatusService requestStatusService;

    @PostMapping
    public ResponseEntity<ClientRequestCreatedResponse> create(
            @Valid @RequestBody ClientRequestCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            String idempotencyKey
    ) {
        validateIdempotencyKey(idempotencyKey);

        IdempotencyResult<ClientRequestCreatedResponse> result =
                clientRequestService.create(request, idempotencyKey);

        return ResponseEntity.status(result.status())
                .body(result.body());
    }

    @PostMapping("/{requestId}/cancel")
    public RequestStatusResponse cancel(@PathVariable UUID requestId) {
        return requestStatusService.cancelOwnRequest(requestId);
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isBlank()) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    null,
                    List.of(ValidationError.ofParameter(
                            "Idempotency-Key",
                            "обязательный заголовок не передан или пуст"
                    ))
            );
        }

        if (key.length() > 128) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    null,
                    List.of(ValidationError.ofParameter(
                            "Idempotency-Key",
                            "должен содержать не более 128 символов"
                    ))
            );
        }
    }
}
