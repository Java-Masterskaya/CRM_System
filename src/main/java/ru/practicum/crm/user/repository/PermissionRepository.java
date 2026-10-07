package ru.practicum.crm.user.repository;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.PermissionEntity;

public interface PermissionRepository extends Repository<PermissionEntity, UUID> {

    PermissionEntity save(PermissionEntity permission);

    @Query(
            value =
                    """
                    SELECT DISTINCT p.code
                    FROM permissions p
                    JOIN role_permissions rp
                      ON p.id = rp.permission_id
                     AND p.tenant_id = rp.tenant_id
                    JOIN roles r
                      ON r.id = rp.role_id
                     AND r.tenant_id = rp.tenant_id
                    JOIN user_roles ur
                      ON ur.role_id = r.id
                     AND ur.tenant_id = r.tenant_id
                    JOIN users u
                      ON u.id = ur.user_id
                     AND u.tenant_id = ur.tenant_id
                    WHERE u.id = :userId
                    """,
            nativeQuery = true
    )
    Set<String> findAllPermissionCodesByUserId(@Param("userId") UUID userId);
}
