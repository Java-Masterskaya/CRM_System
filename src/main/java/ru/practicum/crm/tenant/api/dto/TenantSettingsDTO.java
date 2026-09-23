package ru.practicum.crm.tenant.api.dto;

import jakarta.validation.constraints.AssertTrue;
import java.time.ZoneId;

public record TenantSettingsDTO(
        String timezone
) {
    @AssertTrue(message = "Invalid IANA timezone")
    public boolean isTimezoneValid() {
        return timezone == null
               || ZoneId.getAvailableZoneIds().contains(timezone);
    }
}
