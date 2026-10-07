package ru.practicum.crm.user.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import ru.practicum.crm.user.domain.RolePermissionEntity;
import ru.practicum.crm.user.domain.RolePermissionId;

public interface RolePermissionRepository
        extends Repository<RolePermissionEntity, RolePermissionId> {

    RolePermissionEntity save(RolePermissionEntity rolePermission);

    @Query("select rp from RolePermissionEntity rp where rp.id.roleId = :roleId")
    List<RolePermissionEntity> findByRoleId(@Param("roleId") UUID roleId);
}
