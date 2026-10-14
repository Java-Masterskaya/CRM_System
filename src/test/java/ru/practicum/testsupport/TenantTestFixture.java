package ru.practicum.testsupport;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.repository.TenantRepository;

@TestConfiguration
@RequiredArgsConstructor
public class TenantTestFixture {

    private final TenantRepository tenantRepository;
    private final JdbcTemplate jdbcTemplate;

    public UUID createTenant() {
        String slug = "tenant-" + UUID.randomUUID().toString().replace("-", "");
        return tenantRepository.save(
                new Tenant("Integration test tenant", slug)
        ).getId();
    }

    public UUID createUser(UUID tenantId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO users (id, tenant_id, email, password_hash)"
                + " VALUES (?, ?, ?, 'hash')", id, tenantId, id + "@example.com");
        return id;
    }

    public UUID createRequest(UUID tenantId, UUID authorId) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, subject, description, status,"
                        + " author_id, created_at, updated_at)"
                        + " VALUES (?, ?, 'Выгрузка за квартал', 'Нужен отчёт', 'NEW', ?, now(),"
                        + " now())",
                id, tenantId, authorId);
        return id;
    }
}
