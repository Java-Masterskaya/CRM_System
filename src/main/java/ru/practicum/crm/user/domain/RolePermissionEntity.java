package ru.practicum.crm.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "role_permissions")
public class RolePermissionEntity {

    @EmbeddedId
    private RolePermissionId id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    public RolePermissionEntity() {
    }

    public RolePermissionEntity(RolePermissionId id, UUID tenantId) {
        this.id = copy(id);
        this.tenantId = tenantId;
    }

    public RolePermissionId getId() {
        return copy(id);
    }

    public void setId(RolePermissionId id) {
        this.id = copy(id);
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    private RolePermissionId copy(RolePermissionId source) {
        if (source == null) {
            return null;
        }
        return new RolePermissionId(source.getRoleId(), source.getPermissionId());
    }
}
