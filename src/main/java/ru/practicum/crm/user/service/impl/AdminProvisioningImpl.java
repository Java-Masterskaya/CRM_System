package ru.practicum.crm.user.service.impl;

import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.security.api.PasswordPolicy;
import ru.practicum.crm.user.api.provisioning.AdminProvisioning;
import ru.practicum.crm.user.domain.RoleEntity;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;
import ru.practicum.crm.user.domain.UserStatus;
import ru.practicum.crm.user.repository.RoleRepository;
import ru.practicum.crm.user.repository.UserRepository;
import ru.practicum.crm.user.repository.UserRoleRepository;

@Service
@RequiredArgsConstructor
public class AdminProvisioningImpl implements AdminProvisioning {

    private static final String ADMIN_ROLE_CODE = "ADMIN";

    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final UserRoleRepository userRoleRepository;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final EntityManager entityManager;

    @Override
    @Transactional
    public UUID createInitialAdmin(
            UUID tenantId,
            String email,
            String password
    ) {
        passwordPolicy.validate(password);

        RoleEntity adminRole = roleRepository
                .findByTenantIdAndCode(tenantId, ADMIN_ROLE_CODE)
                .orElseThrow(() -> new ApiException(
                        ErrorCode.INTERNAL_ERROR,
                        "Не удалось найти роль администратора арендатора."
                ));

        UserEntity admin = new UserEntity(
                tenantId,
                email,
                passwordEncoder.encode(password),
                UserStatus.ACTIVE
        );

        userRepository.save(admin);
        entityManager.flush();

        UserRoleEntity userRole = new UserRoleEntity(
                new UserRoleId(
                        admin.getId(),
                        adminRole.getId()
                ),
                tenantId
        );

        userRoleRepository.save(userRole);
        entityManager.flush();

        return admin.getId();
    }
}
