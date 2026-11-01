package ru.practicum.crm.user.service;

import jakarta.validation.Validator;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.common.idempotency.IdempotencyService;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.PasswordPolicy;
import ru.practicum.crm.user.api.UserContext;
import ru.practicum.crm.user.api.dto.AdminUserCreateRequest;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleRepository;

/** Создание оператора или администратора в текущем арендаторе. */
@Service
@RequiredArgsConstructor
public class AdminUserCreationService {

    private static final String OPERATION = "ADMIN_USER_CREATE";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantContext tenantContext;
    private final UserContext userContext;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final Validator validator;
    private final IdempotencyService idempotencyService;

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public IdempotencyResult<AdminUserResponse> create(AdminUserCreateRequest request,
            String idempotencyKey) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, null);
        }
        validateRequest(request, idempotencyKey);
        UUID actorId = userContext.getCurrentUserId();
        AdminUserCreateRequest normalized = new AdminUserCreateRequest(request.email(),
                request.name(), request.temporaryPassword(),
                request.roles().stream().sorted().toList());

        return idempotencyService.execute(tenantId, actorId, OPERATION, idempotencyKey,
                normalized, HttpStatus.CREATED.value(), AdminUserResponse.class,
                () -> createUser(tenantId, normalized));
    }

    private AdminUserResponse createUser(UUID tenantId, AdminUserCreateRequest request) {
        List<ValidationError> passwordErrors = passwordPolicy
                .violations(request.temporaryPassword())
                .stream().map(message -> ValidationError.ofField("temporaryPassword", message))
                .toList();
        rejectIfAny(passwordErrors);

        List<RoleEntity> roles = request.roles().stream()
                .map(code -> roleRepository.findByTenantIdAndCode(tenantId, code)
                        .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, null,
                                List.of(ValidationError.ofField("roles",
                                        "роль недоступна у текущего арендатора")))))
                .toList();
        UUID userId = UUID.randomUUID();
        String passwordHash = passwordEncoder.encode(request.temporaryPassword());
        if (userRepository.insertActiveUser(userId, tenantId, request.email(), request.name(),
                passwordHash) == 0) {
            throw new ApiException(ErrorCode.ALREADY_EXISTS, "Email уже занят у этого арендатора.",
                    List.of(ValidationError.ofField("email", "уже используется")));
        }
        for (RoleEntity role : roles) {
            userRoleRepository.save(new UserRoleEntity(
                    new UserRoleId(userId, role.getId()), tenantId));
        }
        return new AdminUserResponse(userId, request.email(), request.name(),
                UserAccountStatus.ACTIVE, request.roles());
    }

    private void validateRequest(AdminUserCreateRequest request, String idempotencyKey) {
        if (request == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    List.of(ValidationError.ofField(null, "тело запроса обязательно")));
        }
        List<ValidationError> errors = validator.validate(request).stream()
                .map(violation -> ValidationError.ofField(
                        violation.getPropertyPath().iterator().next().getName(),
                        violation.getMessage()))
                .sorted(Comparator.comparing(ValidationError::pointer)
                        .thenComparing(ValidationError::detail))
                .toList();
        rejectIfAny(errors);
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    List.of(ValidationError.ofParameter("Idempotency-Key",
                            "требуется непустой ключ длиной не более 128 символов")));
        }
    }

    private static void rejectIfAny(List<ValidationError> errors) {
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
    }
}
