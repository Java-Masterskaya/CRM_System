package ru.practicum.crm.user.api.provisioning;

import java.util.UUID;

public interface AdminProvisioning {

    UUID createInitialAdmin(
            UUID tenantId,
            String email,
            String password
    );
}
