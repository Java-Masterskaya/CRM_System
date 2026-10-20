package ru.practicum.crm.request.service;

import static org.assertj.core.api.AssertionsForClassTypes.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestSlaMetricsTest {

    private MeterRegistry registry;
    private RequestSlaMetrics slaMetrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        slaMetrics = new RequestSlaMetrics(registry);
    }

    @Test
    void givenTwoTenants_whenBreachesRecorded_thenCountersAreSplitByTenantAndDeadline() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        slaMetrics.firstResponseBreached(first);
        slaMetrics.firstResponseBreached(first);
        slaMetrics.resolutionBreached(second);

        assertThat(count(
                RequestSlaMetrics.BREACHES, RequestSlaMetrics.DEADLINE,
                RequestSlaMetrics.FIRST_RESPONSE, first)).isEqualTo(2);
        assertThat(count(
                RequestSlaMetrics.BREACHES, RequestSlaMetrics.DEADLINE,
                RequestSlaMetrics.RESOLUTION, second)).isEqualTo(1);
        assertThat(registry.find(RequestSlaMetrics.BREACHES)
                .tag(RequestSlaMetrics.TENANT, first.toString())
                .tag(RequestSlaMetrics.DEADLINE, RequestSlaMetrics.RESOLUTION)
                .counter()).isNull();
    }

    @Test
    void givenClosedOnTimeAndBreached_whenRecorded_thenDifferentCountersIncrease() {
        UUID tenantId = UUID.randomUUID();

        slaMetrics.closed(tenantId, true);
        slaMetrics.closed(tenantId, false);
        slaMetrics.closed(tenantId, false);

        assertThat(count(RequestSlaMetrics.CLOSED, RequestSlaMetrics.OUTCOME,
                RequestSlaMetrics.ON_TIME, tenantId)).isEqualTo(1);
        assertThat(count(RequestSlaMetrics.CLOSED, RequestSlaMetrics.OUTCOME,
                RequestSlaMetrics.BREACHED, tenantId)).isEqualTo(2);
    }

    private double count(String name, String kind, String value, UUID tenantId) {
        return registry.get(name)
                .tag(kind, value)
                .tag(RequestSlaMetrics.TENANT, tenantId.toString())
                .counter()
                .count();
    }
}
