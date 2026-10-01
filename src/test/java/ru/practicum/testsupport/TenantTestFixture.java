package ru.practicum.testsupport;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.test.context.TestConfiguration;
import ru.practicum.crm.tenant.domain.Tenant;
import ru.practicum.crm.tenant.repository.TenantRepository;

@TestConfiguration
@RequiredArgsConstructor
public class TenantTestFixture {

    private final TenantRepository tenantRepository;

    public UUID createTenant() {
        return tenantRepository.save(
                new Tenant("Integration test tenant")
        ).getId();
    }
}
