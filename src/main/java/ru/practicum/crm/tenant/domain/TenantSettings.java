package ru.practicum.crm.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapsId;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters.ZoneIdConverter;

@Entity
@Table(name = "tenant_settings")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantSettings {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Setter
    @Column(nullable = false, length = 64)
    @Convert(converter = ZoneIdConverter.class)
    private ZoneId timezone;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @MapsId
    @JoinColumn(name = "tenant_id")
    private Tenant tenant;

    public TenantSettings(Tenant tenant, ZoneId timezone) {
        this.tenant = tenant;
        this.timezone = timezone;
    }

    public static TenantSettings createDefaults(Tenant tenant) {
        TenantSettings settings = new TenantSettings();
        settings.tenant = tenant;
        settings.timezone = ZoneId.of("UTC");
        return settings;
    }
}
