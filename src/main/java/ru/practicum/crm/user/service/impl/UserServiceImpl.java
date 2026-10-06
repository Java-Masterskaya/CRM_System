package ru.practicum.crm.user.service.impl;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.user.api.PasswordPolicy;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.AuthenticatedUserDto;
import ru.practicum.crm.user.api.dto.ChangePasswordRequest;
import ru.practicum.crm.user.api.dto.CreateUserRequest;
import ru.practicum.crm.user.api.dto.UserDto;
import ru.practicum.crm.user.api.mapper.UserMapper;
import ru.practicum.crm.user.context.UserContext;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.UserRepository;

@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;

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

    @Override
    @Transactional(readOnly = true)
    public AuthenticatedUserDto authenticate(UUID tenantId, String email, String password) {
        UserEntity user = userRepository.findByTenantIdAndEmail(tenantId, email)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INVALID_CREDENTIALS,
                        ErrorCode.INVALID_CREDENTIALS.getDefaultDetail()
                ));

        if (!user.canLogIn() || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS,
                    ErrorCode.INVALID_CREDENTIALS.getDefaultDetail());
        }

        return userMapper.toAuthenticatedUserDto(user);
    }

    private UserEntity findUserById(UUID userId, UUID tenantId) {
        return userRepository.findById(userId, tenantId)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.NOT_FOUND,
                        "Пользователь с id " + userId + " не найден. У tenantId: " + tenantId
                ));
    }
}
