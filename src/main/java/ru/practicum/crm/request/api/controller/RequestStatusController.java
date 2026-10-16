package ru.practicum.crm.request.api.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.request.api.dto.ChangeRequestStatusRequest;
import ru.practicum.crm.request.api.dto.RequestStatusResponse;
import ru.practicum.crm.request.service.RequestStatusService;

/**
 * Смена статуса заявки оператором или администратором (T-044). Право на операцию проверяет
 * сервис ({@code REQUEST_STATUS_CHANGE}); клиент его не имеет и получает 403.
 */
@RestController
@RequestMapping("/admin/requests/{requestId}/status")
public class RequestStatusController {

    private final RequestStatusService service;

    public RequestStatusController(RequestStatusService service) {
        this.service = service;
    }

    @PutMapping
    public RequestStatusResponse changeStatus(@PathVariable UUID requestId,
            @Valid @RequestBody ChangeRequestStatusRequest request) {
        return service.changeStatus(requestId, request);
    }
}
