package ru.practicum.crm.tenant.domain;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import ru.practicum.crm.common.model.BaseEntity;

@Entity
@Table(name = "tenants")
@Getter
@NoArgsConstructor
public class Tenant extends BaseEntity {

    @Column(name = "slug", nullable = false, unique = true, length = 100)
    private String slug;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private boolean active;

    @OneToOne(
            mappedBy = "tenant",
            cascade = CascadeType.ALL,
            orphanRemoval = true
    )
    private TenantSettings settings;

    public Tenant(String name, String slug) {
        this.name = name;
        this.slug = slug;
        this.active = true;
    }

    public Tenant(String name, String slug, boolean active) {
        this.name = name;
        this.slug = slug;
        this.active = active;
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    """
                    TenantSettings  - JPA entity управляемая через Tenant OneToOne связь
                    Поэтому безопасно передавать ссылку на объект TenantSettings
                    в метод initializeSettings, копия нарушает целостность данных
                    и может привести к ошибкам при работе с JPA
                    """
    )
    public void initializeSettings(TenantSettings settings) {
        this.settings = settings;
    }
}
