package ru.practicum.crm.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.practicum.crm.common.model.BaseEntity;

@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor
public class Tenant extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    public Tenant(String name) {
        this.name = name;
        this.active = true;
    }

    public Tenant(String name, boolean active) {
        this.name = name;
        this.active = active;
    }
}
