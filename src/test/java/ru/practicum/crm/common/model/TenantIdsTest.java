package ru.practicum.crm.common.model;

import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TenantIdsTest {

    private final UUID fromRequest = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @AfterEach
    void clearContext() {
        TenantIds.clear();
    }

    @Test
    void resolve_whenContextSet_ignoreRequestedTenant() {
        UUID fromToken = UUID.fromString("11111111-1111-1111-1111-111111111111");
        TenantIds.set(fromToken);

        assertThat(fromRequest).isNotEqualTo(fromToken);
        assertThat(TenantIds.resolve(fromRequest)).isEqualTo(fromToken);
    }

    @Test
    void resolve_whenContextEmpty_keepRequestedTenant() {
        assertThat(TenantIds.resolve(fromRequest)).isEqualTo(fromRequest);
        assertThat(TenantIds.get()).isNull();
    }

}
