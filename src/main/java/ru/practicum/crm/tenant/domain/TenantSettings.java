package ru.practicum.crm.tenant.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.ZoneId;
import java.time.ZoneOffset;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.jpa.convert.threeten.Jsr310JpaConverters.ZoneIdConverter;
import ru.practicum.crm.common.model.TenantScopedEntity;

@Entity
@Table(name = "tenant_settings")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TenantSettings extends TenantScopedEntity {
    @Column(nullable = false, length = 64)
    @Convert(converter = ZoneIdConverter.class)
    private ZoneId timezone;

    public static TenantSettings createDefaultSettings() {
        TenantSettings settings = new TenantSettings();
        settings.timezone = ZoneOffset.UTC;
        return settings;
    }

    public TenantSettings(ZoneId timezone) {
        this.timezone = timezone;
    }
}
