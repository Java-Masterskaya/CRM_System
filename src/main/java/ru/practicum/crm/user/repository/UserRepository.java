package ru.practicum.crm.user.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.UserEntity;
import ru.practicum.crm.user.domain.UserStatus;

public interface UserRepository extends Repository<UserEntity, UUID> {

    @Query(nativeQuery = true, value =
            """
            SELECT access_token_version AS version, (status = 'ACTIVE') AS active
            FROM users
            WHERE tenant_id = :tenantId AND id = :userId AND deleted_at IS NULL
            FOR NO KEY UPDATE
            """)
    Optional<UserTokenStateView> findTokenStateForUpdate(@Param("tenantId") UUID tenantId,
            @Param("userId") UUID userId);

    @Query(nativeQuery = true, value =
            """
            SELECT CASE WHEN :version = 0 THEN EXISTS (
                SELECT 1 FROM users WHERE tenant_id = :tenantId AND id = :userId
                  AND (access_token_version > 0 OR status = 'BLOCKED' OR deleted_at IS NOT NULL)
            ) ELSE NOT EXISTS (
                SELECT 1 FROM users WHERE tenant_id = :tenantId AND id = :userId
                  AND access_token_version > 0 AND access_token_version = :version
                  AND status = 'ACTIVE' AND deleted_at IS NULL
            ) END
            """)
    boolean isAccessTokenRevoked(@Param("tenantId") UUID tenantId, @Param("userId") UUID userId,
            @Param("version") long version);

    UserEntity save(UserEntity user);

    /** Уникальность email проверяет БД, в том числе при одновременном создании. */
    @Modifying
    @Query(nativeQuery = true, value =
            """
            INSERT INTO users (id, tenant_id, email, name, password_hash, status,
                               created_at, updated_at)
            VALUES (:id, :tenantId, :email, :name, :passwordHash, 'ACTIVE', NOW(), NOW())
            ON CONFLICT (tenant_id, lower(email)) WHERE deleted_at IS NULL DO NOTHING
            """)
    int insertActiveUser(@Param("id") UUID id, @Param("tenantId") UUID tenantId,
            @Param("email") String email, @Param("name") String name,
            @Param("passwordHash") String passwordHash);

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

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query(
            """
            SELECT u FROM UserEntity u
            WHERE u.id = :id AND u.tenantId = :tenantId AND u.deletedAt IS NULL
            """)
    Optional<UserEntity> findByIdForUpdate(@Param("id") UUID id,
            @Param("tenantId") UUID tenantId);

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

    /** EXISTS сохраняет одну строку на пользователя независимо от числа его ролей. */
    @Query(value =
            """
            SELECT new ru.practicum.crm.user.repository.UserSummary(u.id, u.email, u.name, u.status)
            FROM UserEntity u
            WHERE u.tenantId = :tenantId AND u.deletedAt IS NULL
              AND (:status IS NULL OR u.status = :status)
              AND (:role IS NULL OR EXISTS (
                  SELECT 1 FROM UserRoleEntity ur, RoleEntity r
                  WHERE ur.id.userId = u.id AND ur.tenantId = u.tenantId
                    AND r.id = ur.id.roleId AND r.tenantId = ur.tenantId AND r.code = :role
              ))
            """,
            countQuery =
            """
            SELECT COUNT(u) FROM UserEntity u
            WHERE u.tenantId = :tenantId AND u.deletedAt IS NULL
              AND (:status IS NULL OR u.status = :status)
              AND (:role IS NULL OR EXISTS (
                  SELECT 1 FROM UserRoleEntity ur, RoleEntity r
                  WHERE ur.id.userId = u.id AND ur.tenantId = u.tenantId
                    AND r.id = ur.id.roleId AND r.tenantId = ur.tenantId AND r.code = :role
              ))
            """)
    Page<UserSummary> findUserPage(@Param("tenantId") UUID tenantId,
            @Param("role") String role, @Param("status") UserStatus status, Pageable pageable);

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
