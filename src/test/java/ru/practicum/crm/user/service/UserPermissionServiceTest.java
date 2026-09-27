package ru.practicum.crm.user.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.crm.user.repository.PermissionRepository;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserPermissionServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private UserPermissionService userPermissionService;

    @Test
    @DisplayName("Должен возвращать объединение кодов прав для существующего userId")
    void shouldReturnUnionOfPermissionCodesForUserId() {
        UUID userId = UUID.randomUUID();
        Set expectedPermissions = Set.of("ticket:read", "ticket:write", "user:manage");
        when(permissionRepository.findAllPermissionCodesByUserId(userId)).thenReturn(expectedPermissions);

        Set actualPermissions = userPermissionService.getUserPermissionCodes(userId);

        assertThat(actualPermissions).containsExactlyInAnyOrder("ticket:read", "ticket:write", "user:manage");
    }

    @Test
    @DisplayName("Должен возвращать пустой Set, если userId равен null")
    void shouldReturnEmptySetWhenUserIdIsNull() {
        Set actualPermissions = userPermissionService.getUserPermissionCodes(null);

        assertThat(actualPermissions).isEmpty();
        verifyNoInteractions(permissionRepository);
    }

    @Test
    @DisplayName("hasPermission должен возвращать true, если право содержится в наборе")
    void hasPermission_ShouldReturnTrue_WhenPermissionExists() {
        UUID userId = UUID.randomUUID();
        when(permissionRepository.findAllPermissionCodesByUserId(userId))
                .thenReturn(Set.of("ticket:read", "ticket:write"));

        assertThat(userPermissionService.hasPermission(userId, "ticket:read")).isTrue();
    }

    @Test
    @DisplayName("hasPermission должен возвращать false, если права нет или передан пустой код")
    void hasPermission_ShouldReturnFalse_WhenPermissionMissingOrCodeInvalid() {
        UUID userId = UUID.randomUUID();
        when(permissionRepository.findAllPermissionCodesByUserId(userId))
                .thenReturn(Set.of("ticket:read"));

        assertThat(userPermissionService.hasPermission(userId, "ticket:delete")).isFalse();
        assertThat(userPermissionService.hasPermission(userId, "")).isFalse();
        assertThat(userPermissionService.hasPermission(null, "ticket:read")).isFalse();
    }

}
