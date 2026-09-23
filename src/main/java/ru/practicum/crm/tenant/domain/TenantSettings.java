package ru.practicum.crm.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantSettings {

    @Id
    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(nullable = false, length = 64)
    @Convert(converter = ZoneIdConverter.class)
    private ZoneId timezone;

    @Column(nullable = false, updatable = false)
    @CreationTimestamp
    private OffsetDateTime createdAt;

    @Column(nullable = false)
    @UpdateTimestamp
    private OffsetDateTime updatedAt;

    public TenantSettings(UUID tenantId, ZoneId timezone) {
        this.tenantId = tenantId;
        this.timezone = timezone;
    }

    public static TenantSettings createDefaults(UUID tenantId) {
        return new TenantSettings(tenantId, ZoneOffset.UTC);
    }
}
