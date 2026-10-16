package ru.practicum.crm.user.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.UserEntity;

public interface UserRepository extends Repository<UserEntity, UUID> {

    UserEntity save(UserEntity user);

    @Query(
            """
            SELECT u
            FROM UserEntity u
            WHERE u.id = :id
              AND u.tenantId = :tenantId
              AND u.deletedAt IS NULL
            """
    )
    Optional<UserEntity> findById(
            @Param("id") UUID id,
            @Param("tenantId") UUID tenantId
    );

    @Query(
            """
            SELECT u
            FROM UserEntity u
            WHERE u.tenantId = :tenantId
              AND LOWER(u.email) = LOWER(:email)
              AND u.deletedAt IS NULL
            """
    )
    Optional<UserEntity> findByTenantIdAndEmail(
            @Param("tenantId") UUID tenantId,
            @Param("email") String email
    );

    @Query(
            """
            SELECT u
            FROM UserEntity u
            WHERE u.tenantId = :tenantId
              AND u.deletedAt IS NULL
            """
    )
    List<UserEntity> findAllActiveByTenantId(@Param("tenantId") UUID tenantId);

    /**
     * Активные пользователи арендатора, у которых есть право через любую из их ролей, — по
     * порядку адресов. Связи ролей и прав ищутся в пределах арендатора, как в
     * {@code PermissionRepository.findAllPermissionCodesByUserId}.
     */
    @Query(nativeQuery = true, value =
            """
            SELECT DISTINCT u.*
            FROM users u
            JOIN user_roles ur ON ur.user_id = u.id AND ur.tenant_id = u.tenant_id
            JOIN role_permissions rp ON rp.role_id = ur.role_id AND rp.tenant_id = ur.tenant_id
            JOIN permissions p ON p.id = rp.permission_id AND p.tenant_id = rp.tenant_id
            WHERE u.tenant_id = :tenantId AND p.code = :permissionCode
              AND u.status = 'ACTIVE' AND u.deleted_at IS NULL
            ORDER BY u.email
            """)
    List<UserEntity> findAllActiveWithPermission(@Param("tenantId") UUID tenantId,
            @Param("permissionCode") String permissionCode);
}
