package ru.practicum.crm.request.api.dto;

import java.time.OffsetDateTime;
import java.util.UUID;
import ru.practicum.crm.request.domain.AuditedField;

public record RequestAuditEntryItem(
        UUID id,
        AuditedField field,
        String oldValue,
        String newValue,
        UUID authorId,
        OffsetDateTime createdAt
) {
}
