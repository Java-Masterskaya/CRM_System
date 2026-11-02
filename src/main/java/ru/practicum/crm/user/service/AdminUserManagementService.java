package ru.practicum.crm.user.service;

import jakarta.validation.Validator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.idempotency.IdempotencyResult;
import ru.practicum.crm.common.idempotency.IdempotencyService;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.UserContext;
import ru.practicum.crm.user.api.UserRefreshTokenRevoker;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.AdminUserRolesUpdateRequest;
import ru.practicum.crm.user.api.dto.AdminUserStatusUpdateRequest;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleCode;
import ru.practicum.crm.user.repository.UserRoleRepository;

/** Блокировка, разблокировка и замена назначенных ролей в пределах текущего арендатора. */
@Service
@RequiredArgsConstructor
public class AdminUserManagementService {

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantContext tenantContext;
    private final UserContext userContext;
    private final UserRefreshTokenRevoker refreshTokenRevoker;
    private final IdempotencyService idempotencyService;
    private final Validator validator;

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public IdempotencyResult<AdminUserResponse> updateStatus(UUID userId,
            AdminUserStatusUpdateRequest request, String idempotencyKey) {
        UUID tenantId = requireTenant();
        validateRequest(userId, request, idempotencyKey);
        UUID actorId = userContext.getCurrentUserId();
        return idempotencyService.execute(tenantId, actorId, "ADMIN_USER_STATUS_UPDATE",
                idempotencyKey, new StatusUpdate(userId, request.status()), HttpStatus.OK.value(),
                AdminUserResponse.class,
                () -> changeStatus(tenantId, actorId, userId, request.status()));
    }

    @PreAuthorize("hasAuthority('USER_MANAGE')")
    @Transactional
    public IdempotencyResult<AdminUserResponse> updateRoles(UUID userId,
            AdminUserRolesUpdateRequest request, String idempotencyKey) {
        UUID tenantId = requireTenant();
        validateRequest(userId, request, idempotencyKey);
        UUID actorId = userContext.getCurrentUserId();
        List<String> codes = request.roles().stream().sorted().toList();
        return idempotencyService.execute(tenantId, actorId, "ADMIN_USER_ROLES_UPDATE",
                idempotencyKey, new RolesUpdate(userId, codes), HttpStatus.OK.value(),
                AdminUserResponse.class, () -> changeRoles(tenantId, actorId, userId, codes));
    }

    private AdminUserResponse changeStatus(UUID tenantId, UUID actorId, UUID userId,
            UserAccountStatus status) {
        UserEntity user = requireLockedUser(tenantId, userId);
        if (userId.equals(actorId) && status == UserAccountStatus.BLOCKED) {
            throw new ApiException(ErrorCode.USER_SELF_BLOCK_FORBIDDEN, null);
        }
        UserStatus desired = UserStatus.valueOf(status.name());
        if (user.getStatus() != desired) {
            user.setStatus(desired);
            if (desired == UserStatus.BLOCKED) {
                refreshTokenRevoker.revokeAll(userId);
            }
        }
        return toResponse(user, roleCodes(tenantId, userId));
    }

    private AdminUserResponse changeRoles(UUID tenantId, UUID actorId, UUID userId,
            List<String> codes) {
        UserEntity user = requireLockedUser(tenantId, userId);
        List<String> previous = roleCodes(tenantId, userId);
        boolean removesAdmin = previous.contains("ADMIN") && !codes.contains("ADMIN");
        if (userId.equals(actorId) && removesAdmin) {
            throw new ApiException(ErrorCode.USER_SELF_ADMIN_ROLE_REMOVAL_FORBIDDEN, null);
        }
        List<RoleEntity> roles = codes.stream()
                .map(code -> roleRepository.findByTenantIdAndCode(tenantId, code)
                        .orElseThrow(() -> new ApiException(ErrorCode.VALIDATION_FAILED, null,
                                List.of(ValidationError.ofField("roles",
                                        "роль недоступна у текущего арендатора")))))
                .toList();
        if (!previous.equals(codes)) {
            userRoleRepository.deleteByUserIdAndTenantId(userId, tenantId);
            for (RoleEntity role : roles) {
                userRoleRepository.save(new UserRoleEntity(
                        new UserRoleId(userId, role.getId()), tenantId));
            }
            if (removesAdmin) {
                user.revokeAccessTokens();
                refreshTokenRevoker.revokeAll(userId);
            }
        }
        return toResponse(user, codes);
    }

    private UserEntity requireLockedUser(UUID tenantId, UUID userId) {
        return userRepository.findByIdForUpdate(userId, tenantId)
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND, null));
    }

    private List<String> roleCodes(UUID tenantId, UUID userId) {
        return userRoleRepository.findRoleCodesByUserIds(tenantId, List.of(userId)).stream()
                .map(UserRoleCode::code).sorted().toList();
    }

    private AdminUserResponse toResponse(UserEntity user, List<String> roles) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getName(),
                UserAccountStatus.valueOf(user.getStatus().name()), roles);
    }

    private UUID requireTenant() {
        UUID tenantId = tenantContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, null);
        }
        return tenantId;
    }

    private void validateRequest(UUID userId, Object request, String idempotencyKey) {
        if (userId == null || request == null) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null);
        }
        List<ValidationError> errors = validator.validate(request).stream()
                .map(violation -> ValidationError.ofField(
                        violation.getPropertyPath().iterator().next().getName(),
                        violation.getMessage())).toList();
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 128) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    List.of(ValidationError.ofParameter("Idempotency-Key",
                            "требуется непустой ключ длиной не более 128 символов")));
        }
    }

    private record StatusUpdate(UUID userId, UserAccountStatus status) {
    }

    private record RolesUpdate(UUID userId, List<String> roles) {
        private RolesUpdate {
            roles = List.copyOf(roles);
        }
    }
}
