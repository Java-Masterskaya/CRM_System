package ru.practicum.crm.user.service;

import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.user.api.UserPermissionService;
import ru.practicum.crm.user.domain.PermissionCode;
import ru.practicum.crm.user.repository.PermissionRepository;

@Service
public class UserPermissionServiceImpl implements UserPermissionService {

    private final PermissionRepository permissionRepository;

    public UserPermissionServiceImpl(PermissionRepository permissionRepository) {
        this.permissionRepository = permissionRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> getUserPermissionCodes(UUID userId) {
        if (userId == null) {
            return Set.of();
        }
        return permissionRepository.findAllPermissionCodesByUserId(userId);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPermission(UUID userId, PermissionCode permissionCode) {
        if (userId == null || permissionCode == null) {
            return false;
        }

        Set<String> userPermissions = getUserPermissionCodes(userId);
        return userPermissions.contains(permissionCode.name());
    }
}
