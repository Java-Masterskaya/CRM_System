package ru.practicum.crm.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;
import ru.practicum.crm.common.model.TenantScopedEntity;

@Entity
@Table(name = "permissions")
public class PermissionEntity extends TenantScopedEntity {

    @Column(name = "code", nullable = false, length = 100)
    private String code;

    @Column(name = "description")
    private String description;

    protected PermissionEntity() {
    }

    public PermissionEntity(UUID tenantId, String code, String description) {
        super(tenantId);
        this.code = code;
        this.description = description;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }
}
