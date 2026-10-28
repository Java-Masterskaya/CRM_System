package ru.practicum.crm.request.api.controller;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.request.api.dto.RequestStatusResponse;
import ru.practicum.crm.request.service.RequestSoftDeleteService;


/**
 * Мягкое удаление м восстановление заявки администатором (T-047). Права {@code REQUEST_DELETE}
 * проверяет сервис.
 * */
@RestController
@RequestMapping("/admin/requests/{requestId}")
@RequiredArgsConstructor
public class RequestSoftDeleteController {

    private final RequestSoftDeleteService requestService;

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteRequest(@PathVariable UUID requestId) {
        requestService.delete(requestId);
    }

    @PostMapping("/restore")
    public RequestStatusResponse restoreRequest(@PathVariable UUID requestId) {
        return requestService.restore(requestId);
    }
}
