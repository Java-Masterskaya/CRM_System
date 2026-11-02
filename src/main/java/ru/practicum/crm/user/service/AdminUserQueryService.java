package ru.practicum.crm.user.service;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.dto.AdminUserResponse;
import ru.practicum.crm.user.api.dto.UserAccountStatus;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleCode;
import ru.practicum.crm.user.repository.UserRoleRepository;
import ru.practicum.crm.user.repository.UserSummary;

/** Чтение пользователей своего арендатора для административного контура T-111. */
@Service
@RequiredArgsConstructor
public class AdminUserQueryService {

    private static final Set<String> SORT_FIELDS = Set.of("email", "name", "status", "id");
    private static final Pattern ROLE_CODE = Pattern.compile("\\S+");

    private final UserRepository userRepository;
    private final UserRoleRepository userRoleRepository;
    private final TenantContext tenantContext;

    @PreAuthorize("hasAuthority('USER_READ')")
    @Transactional(readOnly = true)
    public PageResponse<AdminUserResponse> list(String role, UserAccountStatus status,
            Integer page, Integer size, List<String> sort) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        if (tenantId == null) {
            throw new ApiException(ErrorCode.UNAUTHENTICATED, null);
        }
        if (role != null && !ROLE_CODE.matcher(role).matches()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null,
                    List.of(ValidationError.ofParameter("role", "некорректный код роли")));
        }

        List<String> resolvedSort = sort == null || sort.isEmpty()
                ? List.of("email,asc") : sort;
        Pageable pageable = PageRequests.toPageable(page, size, resolvedSort, SORT_FIELDS);
        UserStatus userStatus = status == null ? null : UserStatus.valueOf(status.name());
        Page<UserSummary> users = userRepository.findUserPage(tenantId, role, userStatus, pageable);

        Map<UUID, List<String>> roles = users.isEmpty() ? Map.of()
                : userRoleRepository.findRoleCodesByUserIds(tenantId,
                        users.getContent().stream().map(UserSummary::id).toList())
                        .stream().collect(Collectors.groupingBy(UserRoleCode::userId,
                                Collectors.mapping(UserRoleCode::code, Collectors.toList())));

        List<AdminUserResponse> content = users.getContent().stream()
                .map(user -> new AdminUserResponse(user.id(), user.email(), user.name(),
                        UserAccountStatus.valueOf(user.status().name()),
                        roles.getOrDefault(user.id(), List.of())))
                .toList();
        return PageResponse.of(content, users.getNumber(), users.getSize(),
                users.getTotalElements());
    }
}
