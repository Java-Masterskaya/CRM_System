package ru.practicum.crm.tenant.repository;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.tenant.domain.Tenant;

public class TenantRepositoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private TenantRepository repository;

    @Test
    void save_whenTenantCreated_generatesIdAndStoresTimestamps() {
        Tenant tenant = new Tenant("Integration Tenant");

        Tenant saved = repository.save(tenant);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getName()).isEqualTo("Integration Tenant");
        assertThat(saved.isActive()).isTrue();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findById_whenTenantExists_returnsTenant() {
        Tenant saved = repository.save(new Tenant("Find Me"));

        Tenant found = repository.findById(saved.getId()).orElseThrow();

        assertThat(found.getId()).isEqualTo(saved.getId());
        assertThat(found.getName()).isEqualTo("Find Me");
        assertThat(found.isActive()).isTrue();
    }

    @Test
    void save_whenTwoTenantsCreated_storesBothTenants() {
        Tenant first = repository.save(new Tenant("Tenant A"));
        Tenant second = repository.save(new Tenant("Tenant B"));

        assertThat(first.getId()).isNotEqualTo(second.getId());
        assertThat(repository.findById(first.getId()).orElseThrow().getName())
                .isEqualTo("Tenant A");
        assertThat(repository.findById(second.getId()).orElseThrow().getName())
                .isEqualTo("Tenant B");
    }
}
