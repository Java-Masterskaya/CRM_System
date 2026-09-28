package ru.practicum.crm.tenant.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import ru.practicum.crm.tenant.domain.Tenant;

public class TenantTest {

    @Test
    void constructor_whenNameProvided_createsActiveTenant() {
        Tenant tenant = new Tenant("Test Tenant");

        assertThat(tenant.getId()).isNull();
        assertThat(tenant.getName()).isEqualTo("Test Tenant");
        assertThat(tenant.isActive()).isTrue();
        assertThat(tenant.getCreatedAt()).isNull();
        assertThat(tenant.getUpdatedAt()).isNull();
    }

    @Test
    void constructor_whenActiveProvided_keepsActiveState() {
        Tenant tenant = new Tenant("Disabled Tenant", false);

        assertThat(tenant.getName()).isEqualTo("Disabled Tenant");
        assertThat(tenant.isActive()).isFalse();
    }
}
