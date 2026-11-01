package ru.practicum.crm.user.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.UserRoleEntity;
import ru.practicum.crm.user.domain.UserRoleId;

public interface UserRoleRepository
        extends Repository<UserRoleEntity, UserRoleId> {

    UserRoleEntity save(UserRoleEntity userRole);

    @Query("select ur from UserRoleEntity ur where ur.id.userId = :userId")
    List<UserRoleEntity> findByUserId(@Param("userId") UUID userId);

    @Query(
            """
            SELECT new ru.practicum.crm.user.repository.UserRoleCode(ur.id.userId, r.code)
            FROM UserRoleEntity ur, RoleEntity r
            WHERE ur.tenantId = :tenantId AND r.tenantId = ur.tenantId
              AND r.id = ur.id.roleId AND ur.id.userId IN :userIds
            """)
    List<UserRoleCode> findRoleCodesByUserIds(@Param("tenantId") UUID tenantId,
            @Param("userIds") Collection<UUID> userIds);

    @Modifying(flushAutomatically = true)
    @Query(
            """
            DELETE FROM UserRoleEntity ur
            WHERE ur.tenantId = :tenantId AND ur.id.userId = :userId
            """)
    int deleteByUserIdAndTenantId(@Param("userId") UUID userId,
            @Param("tenantId") UUID tenantId);
}
