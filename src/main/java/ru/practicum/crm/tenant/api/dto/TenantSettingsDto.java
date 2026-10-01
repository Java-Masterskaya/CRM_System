package ru.practicum.crm.tenant.api.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.constraints.AssertTrue;
import java.time.ZoneId;

public record TenantSettingsDto(
        String timezone
) {
    @JsonIgnore
    @AssertTrue(message = "Invalid IANA timezone")
    public boolean isTimezoneValid() {
        return timezone == null || ZoneId.getAvailableZoneIds().contains(timezone);
    }
}
