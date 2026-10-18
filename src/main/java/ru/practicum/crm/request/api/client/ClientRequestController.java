package ru.practicum.crm.request.api.client;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.request.api.client.dto.ClientRequestCreateRequest;
import ru.practicum.crm.request.api.client.dto.ClientRequestCreatedResponse;
import ru.practicum.crm.request.service.ClientRequestService;

@RestController
@RequestMapping("/client/requests")
@RequiredArgsConstructor
@Validated
public class ClientRequestController {

    private final ClientRequestService clientRequestService;

    @PostMapping
    public ResponseEntity<ClientRequestCreatedResponse> create(
            @Valid @RequestBody ClientRequestCreateRequest request,
            @RequestHeader("Idempotency-key")
            @NotBlank
            @Size(max = 128)
            String idempotencyKey
    ) {
        IdempotencyResult<ClientRequestCreatedResponse> result =
                clientRequestService.create(request, idempotencyKey);

        return ResponseEntity.status(result.status())
                .body(result.body());
    }
}
