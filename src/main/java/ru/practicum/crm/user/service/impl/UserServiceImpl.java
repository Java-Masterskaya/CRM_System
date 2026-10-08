package ru.practicum.crm.user.service.impl;

import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.security.api.PasswordPolicy;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantLookup;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.ClientProfileDto;
import ru.practicum.crm.user.api.dto.ClientRegistrationRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UpdateClientProfileRequest;
import ru.practicum.crm.user.api.dto.UserDto;
import ru.practicum.crm.user.api.mapper.UserMapper;
import ru.practicum.crm.user.context.UserContext;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleRepository;
import ru.practicum.crm.user.service.UserService;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final TenantLookup tenantLookup;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EntityManager entityManager;

    private final TransactionTemplate transactionTemplate;

    private final TenantContext tenantContext;
    private final UserContext userContext;

    // TODO: В случае необходимости внести изменения в метод create
    @Override
    @Transactional
    public UserDto create(CreateUserRequest request) {
        UUID tenantId = tenantContext.getCurrentTenantId();

        passwordPolicy.validate(request.password());

        String passwordHash = passwordEncoder.encode(request.password());

        UserEntity user = new UserEntity(
                tenantId,
                request.email(),
                passwordHash,
                UserStatus.ACTIVE
        );

        userRepository.save(user);
        return userMapper.toDto(user);
    }

    @Override
    @Transactional
    public void registerClient(ClientRegistrationRequest request) {
        UUID tenantId = tenantLookup.findActiveBySlug(request.tenantSlug())
                .orElseThrow(() -> new ApiException(ErrorCode.NOT_FOUND,
                        "Регистрация для указанного арендатора недоступна."))
                .id();

        String email = request.email().trim();
        if (userRepository.findByTenantIdAndEmail(tenantId, email).isPresent()) {
            throw duplicateEmail();
        }

        passwordPolicy.validate(request.password());
        String passwordHash = passwordEncoder.encode(request.password());
        UserEntity user;
        try {
            user = userRepository.save(new UserEntity(
                    tenantId,
                    email,
                    request.name().trim(),
                    passwordHash,
                    UserStatus.ACTIVE));
            entityManager.flush();
        } catch (RuntimeException exception) {
            if (violatesConstraint(exception, "uk_users_tenant_email")) {
                throw duplicateEmail();
            }
            throw exception;
        }

        RoleEntity clientRole = roleRepository.findByTenantIdAndCode(tenantId, "CLIENT")
                .orElseThrow(() -> new IllegalStateException(
                        "Для арендатора не настроена роль клиента."));
        userRoleRepository.save(new UserRoleEntity(
                new UserRoleId(user.getId(), clientRole.getId()), tenantId));
    }

    @Override
    @Transactional(readOnly = true)
    public ClientProfileDto getClientProfile() {
        UserEntity user = findUserById(
                userContext.getCurrentUserId(), tenantContext.getCurrentTenantId());
        return toClientProfile(user);
    }

    @Override
    @Transactional
    public ClientProfileDto updateClientProfile(UpdateClientProfileRequest request) {
        UserEntity user = findUserById(
                userContext.getCurrentUserId(), tenantContext.getCurrentTenantId());
        user.setName(request.name().trim());
        user.setPhone(request.phone() == null ? null : request.phone().trim());
        return toClientProfile(user);
    }

    private ClientProfileDto toClientProfile(UserEntity user) {
        return new ClientProfileDto(user.getName(), user.getEmail(), user.getPhone());
    }

    private ApiException duplicateEmail() {
        return new ApiException(ErrorCode.ALREADY_EXISTS,
                "Email уже зарегистрирован у этого арендатора.",
                List.of(ValidationError.ofField(
                        "email", "уже используется у этого арендатора")));
    }

    private boolean violatesConstraint(Throwable exception, String constraintName) {
        Throwable cause = exception;
        while (cause != null) {
            if (cause instanceof ConstraintViolationException violation
                    && constraintName.equals(violation.getConstraintName())) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    @Override
    public void changePassword(ChangePasswordRequest request) {
        UUID userId = userContext.getCurrentUserId();
        UUID tenantId = tenantContext.getCurrentTenantId();

        transactionTemplate.executeWithoutResult(status -> {
            UserEntity user = findUserById(userId, tenantId);

            if (!passwordEncoder.matches(
                    request.currentPassword(),
                    user.getPasswordHash())) {
                throw new ApiException(
                        ErrorCode.INVALID_CREDENTIALS,
                        "Текущий пароль указан неверно."
                );
            }

            passwordPolicy.validate(request.newPassword());

            user.setPasswordHash(
                    passwordEncoder.encode(request.newPassword())
            );
        });
    }

    private UserEntity findUserById(UUID userId, UUID tenantId) {
        return userRepository.findById(userId, tenantId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND,
                        "Пользователь с id " + userId + " не найден. У tenantId: " + tenantId
                ));
    }
}
