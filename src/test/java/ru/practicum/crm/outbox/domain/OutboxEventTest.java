package ru.practicum.crm.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private OutboxEvent event;

    @BeforeEach
    void setUp() {
        event = new OutboxEvent(TENANT_ID, "REQUEST_CREATED", Map.of("requestId", "42"));
    }

    @Test
    void newEvent_whenCreated_waitsForDeliveryWithoutAttempts() {
        assertThat(event.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(event.getEventType()).isEqualTo("REQUEST_CREATED");
        assertThat(event.getPayload()).containsEntry("requestId", "42");
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(event.getAttempts()).isZero();
    }

    @Test
    void onCreate_whenCalled_makesEventReadyForImmediateDelivery() {
        event.onCreate();

        assertThat(event.getCreatedAt()).isNotNull();
        assertThat(event.getUpdatedAt()).isEqualTo(event.getCreatedAt());
        assertThat(event.getNextAttemptAt()).isEqualTo(event.getCreatedAt());
    }

    @Test
    void onUpdate_whenCalled_movesUpdatedAtAndLeavesCreatedAtIntact() {
        event.onCreate();
        Instant createdAt = event.getCreatedAt();

        event.onUpdate();

        assertThat(event.getCreatedAt()).isEqualTo(createdAt);
        assertThat(event.getUpdatedAt()).isAfterOrEqualTo(createdAt);
    }

    @Test
    void payload_whenSourceMapChangedAfterCreation_eventKeepsOriginalValues() {
        Map<String, Object> source = new HashMap<>();
        source.put("requestId", "42");
        OutboxEvent created = new OutboxEvent(TENANT_ID, "REQUEST_CREATED", source);

        source.put("requestId", "43");

        assertThat(created.getPayload()).containsEntry("requestId", "42");
    }

    @Test
    void payload_whenReturnedMapChanged_eventKeepsOriginalValues() {
        event.getPayload().put("requestId", "43");

        assertThat(event.getPayload()).containsEntry("requestId", "42");
    }

    @Test
    void equals_whenEventsAreNotSavedYet_distinguishesThemByIdentity() {
        OutboxEvent other = new OutboxEvent(TENANT_ID, "REQUEST_CREATED", Map.of());

        assertThat(event).isEqualTo(event)
                .isNotEqualTo(other)
                .isNotEqualTo(null)
                .isNotEqualTo("не событие");
        assertThat(event.hashCode()).isEqualTo(other.hashCode());
    }
}
