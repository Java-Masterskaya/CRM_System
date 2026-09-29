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
                    JOIN role_permissions rp ON p.id = rp.permission_id
                    JOIN user_roles ur ON rp.role_id = ur.role_id
                    WHERE ur.user_id = :userId
                    """,
            nativeQuery = true
    )
    Set<String> findAllPermissionCodesByUserId(@Param("userId") UUID userId);
}
