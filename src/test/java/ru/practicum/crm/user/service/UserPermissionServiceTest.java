package ru.practicum.crm.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import ru.practicum.crm.user.domain.PermissionCode;
import ru.practicum.crm.user.repository.PermissionRepository;

@ExtendWith(MockitoExtension.class)
class UserPermissionServiceTest {

    @Mock
    private PermissionRepository permissionRepository;

    @InjectMocks
    private UserPermissionServiceImpl userPermissionService;

    @Test
    @DisplayName("Должен возвращать права пользователя")
    void shouldReturnUserPermissionCodes() {
        UUID userId = UUID.randomUUID();

        Set<String> expectedPermissions = Set.of(
                "REQUEST_STATUS_CHANGE",
                "USER_MANAGE",
                "USER_READ"
        );

        when(permissionRepository.findAllPermissionCodesByUserId(userId))
                .thenReturn(expectedPermissions);

        Set<String> actualPermissions =
                userPermissionService.getUserPermissionCodes(userId);

        assertThat(actualPermissions)
                .containsExactlyInAnyOrder(
                        "REQUEST_STATUS_CHANGE",
                        "USER_MANAGE",
                        "USER_READ"
                );
    }

    @Test
    @DisplayName("Должен возвращать пустой Set, если userId равен null")
    void shouldReturnEmptySetWhenUserIdIsNull() {
        Set<String> actualPermissions =
                userPermissionService.getUserPermissionCodes(null);

        assertThat(actualPermissions).isEmpty();
        verifyNoInteractions(permissionRepository);
    }

    @Test
    @DisplayName("hasPermission должен возвращать true, если право существует")
    void hasPermission_shouldReturnTrue_whenPermissionExists() {
        UUID userId = UUID.randomUUID();

        when(permissionRepository.findAllPermissionCodesByUserId(userId))
                .thenReturn(Set.of(
                        "REQUEST_STATUS_CHANGE",
                        "USER_MANAGE"
                ));

        assertThat(
                userPermissionService.hasPermission(
                        userId,
                        PermissionCode.USER_MANAGE
                )
        ).isTrue();
    }

    @Test
    @DisplayName("hasPermission должен возвращать false, если права нет")
    void hasPermission_shouldReturnFalse_whenPermissionMissing() {
        UUID userId = UUID.randomUUID();

        when(permissionRepository.findAllPermissionCodesByUserId(userId))
                .thenReturn(Set.of("USER_MANAGE"));

        assertThat(
                userPermissionService.hasPermission(
                        userId,
                        PermissionCode.USER_READ
                )
        ).isFalse();
    }

    @Test
    @DisplayName("hasPermission должен возвращать false для null userId")
    void hasPermission_shouldReturnFalse_whenUserIdIsNull() {
        assertThat(
                userPermissionService.hasPermission(
                        null,
                        PermissionCode.USER_MANAGE
                )
        ).isFalse();

        verifyNoInteractions(permissionRepository);
    }

    @Test
    @DisplayName("hasPermission должен возвращать false для null кода")
    void hasPermission_shouldReturnFalse_whenPermissionCodeIsInvalid() {
        UUID userId = UUID.randomUUID();

        assertThat(
                userPermissionService.hasPermission(userId, null)
        ).isFalse();

        verifyNoInteractions(permissionRepository);
    }
}
