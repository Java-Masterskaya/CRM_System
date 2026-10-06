package ru.practicum.crm.user.service.impl;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.tenant.api.seeding.TenantAccessSeeder;

@Service
@RequiredArgsConstructor
public class TenantAccessSeederImpl implements TenantAccessSeeder {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional
    public void seedDefaults(UUID tenantId) {
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId must not be null");
        }
        jdbcTemplate.query("SELECT seed_tenant_access_defaults(?)", rs -> null, tenantId);
    }
}
