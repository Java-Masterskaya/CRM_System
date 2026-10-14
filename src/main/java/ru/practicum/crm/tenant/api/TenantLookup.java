package ru.practicum.crm.tenant.api;

import java.util.Optional;

public interface TenantLookup {
    Optional<TenantReference> findActiveReferenceBySlug(String slug);
}
