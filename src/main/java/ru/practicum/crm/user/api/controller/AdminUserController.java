package ru.practicum.crm.user.api.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.user.api.dto.AdminUserCreateRequest;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.AdminUserRolesUpdateRequest;
import ru.practicum.crm.user.api.dto.AdminUserStatusUpdateRequest;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.service.AdminUserCreationService;
import ru.practicum.crm.user.service.AdminUserManagementService;
import ru.practicum.crm.user.service.AdminUserQueryService;

@RestController
@RequiredArgsConstructor
public class AdminUserController implements AdminUserApi {

    private final AdminUserQueryService queryService;
    private final AdminUserCreationService creationService;
    private final AdminUserManagementService managementService;

    @Override
    public PageResponse<AdminUserResponse> list(String role, UserAccountStatus status,
            Integer page, Integer size, HttpServletRequest servletRequest) {
        // Сохраняем запятую внутри одного выражения и порядок повторяющихся параметров.
        String[] sort = servletRequest.getParameterValues("sort");
        return queryService.list(role, status, page, size, sort == null ? null : List.of(sort));
    }

    @Override
    public ResponseEntity<AdminUserResponse> create(AdminUserCreateRequest request,
            String idempotencyKey) {
        IdempotencyResult<AdminUserResponse> result =
                creationService.create(request, idempotencyKey);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @Override
    public ResponseEntity<AdminUserResponse> updateStatus(UUID id,
            AdminUserStatusUpdateRequest request, String idempotencyKey) {
        IdempotencyResult<AdminUserResponse> result = managementService.updateStatus(id, request,
                idempotencyKey);
        return ResponseEntity.status(result.status()).body(result.body());
    }

    @Override
    public ResponseEntity<AdminUserResponse> updateRoles(UUID id,
            AdminUserRolesUpdateRequest request, String idempotencyKey) {
        IdempotencyResult<AdminUserResponse> result = managementService.updateRoles(id, request,
                idempotencyKey);
        return ResponseEntity.status(result.status()).body(result.body());
    }
}
