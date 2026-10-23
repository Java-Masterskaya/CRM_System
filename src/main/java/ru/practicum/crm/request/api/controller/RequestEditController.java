package ru.practicum.crm.request.api.controller;

import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.request.api.dto.EditRequestRequest;
import ru.practicum.crm.request.api.dto.RequestCardResponse;
import ru.practicum.crm.request.service.RequestEditService;

@RestController
@RequestMapping("/admin/requests/{requestId}")
public class RequestEditController {

    private final RequestEditService service;

    public RequestEditController(RequestEditService service) {
        this.service = service;
    }

    @PutMapping
    public RequestCardResponse edit(
            @PathVariable UUID requestId,
            @Valid @RequestBody EditRequestRequest request
    ) {
        return service.edit(requestId, request);
    }
}
