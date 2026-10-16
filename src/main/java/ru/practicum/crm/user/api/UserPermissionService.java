package ru.practicum.crm.user.api;

import java.util.Set;
import java.util.UUID;

public interface UserPermissionService {
    Set<String> getUserPermissionCodes(UUID userId);

    boolean hasPermission(UUID userId, PermissionCode permissionCode);
}
