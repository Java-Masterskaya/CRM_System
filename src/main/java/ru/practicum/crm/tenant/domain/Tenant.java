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

    @Column(nullable = false)
    private String name;

    @Column(name = "slug", nullable = false, unique = true, length = 63)
    private String slug;

    @Column(nullable = false)
    private boolean active;

    @OneToOne(
            mappedBy = "tenant",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.LAZY
    )
    private TenantSettings settings;

    public Tenant(String name, String slug) {
        this(name, slug, true);
    }

    public Tenant(String name) {
        this(name, toSlug(name), true);
    }

    public Tenant(String name, String slug, boolean active) {
        this.name = name;
        this.slug = slug == null || slug.isBlank() ? toSlug(name) : slug;
        this.active = active;
    }

    private static String toSlug(String value) {
        String slugValue = value == null ? "" : value.toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
        return slugValue.isBlank() ? "tenant" : slugValue;
    }

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification =
                    """
                    TenantSettings — JPA entity, управляемая через Tenant OneToOne связь.
                    Поэтому безопасно передавать ссылку на объект TenantSettings
                    в метод initializeSettings. Копирование нарушило бы целостность
                    данных и могло бы привести к ошибкам при работе с JPA.
                    """
    )
    public void initializeSettings(TenantSettings settings) {
        this.settings = settings;
    }
}
