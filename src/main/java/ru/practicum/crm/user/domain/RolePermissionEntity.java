package ru.practicum.crm.user.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "role_permissions")
public class RolePermissionEntity {

    @EmbeddedId
    private RolePermissionId id;

    public RolePermissionEntity() {
    }

    public RolePermissionEntity(RolePermissionId id) {
        this.id = copy(id);
    }

    public RolePermissionId getId() {
        return copy(id);
    }

    public void setId(RolePermissionId id) {
        this.id = copy(id);
    }

    private RolePermissionId copy(RolePermissionId source) {
        if (source == null) {
            return null;
        }
        return new RolePermissionId(source.getRoleId(), source.getPermissionId());
    }
}
