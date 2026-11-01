package ru.practicum.crm.user.api.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.enums.ParameterIn;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.user.api.dto.AdminUserCreateRequest;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.AdminUserRolesUpdateRequest;
import ru.practicum.crm.user.api.dto.AdminUserStatusUpdateRequest;
import ru.practicum.crm.user.api.dto.UserAccountStatus;

/**
 * HTTP-контракт T-111. Обработчики реализованы в {@link AdminUserController}; сам интерфейс
 * не регистрирует дополнительные маршруты.
 */
@Tag(name = "Пользователи арендатора")
@RequestMapping("/admin/users")
public interface AdminUserApi {

    @Operation(summary = "Список пользователей своего арендатора",
            description = "Требуется право USER_READ")
    @GetMapping
    @Parameter(name = "sort", in = ParameterIn.QUERY,
            description = "Сортировка вида email,asc; параметр можно повторять",
            array = @ArraySchema(schema = @Schema(type = "string")))
    PageResponse<AdminUserResponse> list(
            @RequestParam(name = "role", required = false)
            @Pattern(regexp = "\\S+", message = "код роли не должен содержать пробелы")
            String role,
            @RequestParam(name = "status", required = false) UserAccountStatus status,
            @RequestParam(name = "page", defaultValue = "0") @Min(0) Integer page,
            @RequestParam(name = "size", defaultValue = "20") @Min(1) @Max(100) Integer size,
            HttpServletRequest servletRequest
    );

    @Operation(summary = "Создать оператора или администратора",
            description = "Требуется право USER_MANAGE. Успешный ответ — 201")
    @ApiResponse(responseCode = "201", description = "Пользователь создан")
    @PostMapping
    ResponseEntity<AdminUserResponse> create(
            @Valid @RequestBody AdminUserCreateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @NotBlank @Size(max = 128) String idempotencyKey
    );

    @Operation(summary = "Заблокировать или разблокировать пользователя",
            description = "Требуется право USER_MANAGE")
    @PatchMapping("/{id}/status")
    ResponseEntity<AdminUserResponse> updateStatus(
            @PathVariable(name = "id") UUID id,
            @Valid @RequestBody AdminUserStatusUpdateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @NotBlank @Size(max = 128) String idempotencyKey
    );

    @Operation(summary = "Заменить набор ролей пользователя",
            description = "Требуется право USER_MANAGE")
    @PutMapping("/{id}/roles")
    ResponseEntity<AdminUserResponse> updateRoles(
            @PathVariable(name = "id") UUID id,
            @Valid @RequestBody AdminUserRolesUpdateRequest request,
            @RequestHeader(name = "Idempotency-Key", required = false)
            @NotBlank @Size(max = 128) String idempotencyKey
    );
}
