package ru.practicum.crm.tenant.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.practicum.crm.tenant.domain.Tenant;

public class TenantTest {

    @Test
    void constructor_whenNameProvided_createsActiveTenant() {
        Tenant tenant = new Tenant("Test Tenant", "test-tenant");

        assertThat(tenant.getId()).isNull();
        assertThat(tenant.getName()).isEqualTo("Test Tenant");
        assertThat(tenant.getSlug()).isEqualTo("test-tenant");
        assertThat(tenant.isActive()).isTrue();
        assertThat(tenant.getCreatedAt()).isNull();
        assertThat(tenant.getUpdatedAt()).isNull();
    }

    @Test
    void constructor_whenActiveProvided_keepsActiveState() {
        Tenant tenant = new Tenant("Disabled Tenant", "disabled-tenant", false);

        assertThat(tenant.getName()).isEqualTo("Disabled Tenant");
        assertThat(tenant.getSlug()).isEqualTo("disabled-tenant");
        assertThat(tenant.isActive()).isFalse();
    }
}
