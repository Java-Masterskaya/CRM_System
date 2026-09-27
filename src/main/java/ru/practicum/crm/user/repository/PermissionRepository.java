package ru.practicum.crm.user.repository;

import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface PermissionRepository extends JpaRepository {

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
    Set findAllPermissionCodesByUserId(@Param("userId") UUID userId);
}
