package ru.practicum.crm.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "user_roles")
public class UserRoleEntity {

    @EmbeddedId
    private UserRoleId id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    public UserRoleEntity() {
    }

    public UserRoleEntity(UserRoleId id, UUID tenantId) {
        this.id = copy(id);
        this.tenantId = tenantId;
    }

    public UserRoleId getId() {
        return copy(id);
    }

    public void setId(UserRoleId id) {
        this.id = copy(id);
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public void setTenantId(UUID tenantId) {
        this.tenantId = tenantId;
    }

    private UserRoleId copy(UserRoleId source) {
        if (source == null) {
            return null;
        }
        return new UserRoleId(source.getUserId(), source.getRoleId());
    }
}
