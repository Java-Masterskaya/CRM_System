package ru.practicum.crm.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OutboxEventTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final DeliveryFailure SMTP_DOWN =
            new DeliveryFailure("SMTP_DOWN", "Почтовый сервер недоступен");

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
        assertThat(event.getAggregateType()).isNull();
        assertThat(event.getAggregateId()).isNull();
    }

    @Test
    void newEvent_withObjectReference_keepsIt() {
        UUID requestId = UUID.randomUUID();

        OutboxEvent aboutRequest = OutboxEvent.aboutObject(TENANT_ID, "REQUEST_CREATED",
                "REQUEST", requestId, Map.of());

        assertThat(aboutRequest.getAggregateType()).isEqualTo("REQUEST");
        assertThat(aboutRequest.getAggregateId()).isEqualTo(requestId);
        assertThat(aboutRequest.getStatus()).isEqualTo(OutboxStatus.NEW);
    }

    @Test
    void newEvent_withIncompleteObjectReference_isRejected() {
        UUID requestId = UUID.randomUUID();

        assertThatThrownBy(() -> OutboxEvent.aboutObject(TENANT_ID, "REQUEST_CREATED", "REQUEST",
                null, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.aboutObject(TENANT_ID, "REQUEST_CREATED", null,
                requestId, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> OutboxEvent.aboutObject(TENANT_ID, "REQUEST_CREATED", " ",
                requestId, Map.of())).isInstanceOf(IllegalArgumentException.class);
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

    @Test
    void claim_whenNewEventTaken_startsAttemptAndHoldsItUntilLeaseEnds() {
        Instant leaseUntil = Instant.parse("2026-09-24T12:05:00Z");

        event.claim(leaseUntil);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.IN_PROGRESS);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getNextAttemptAt()).isEqualTo(leaseUntil);
    }

    @Test
    void claim_whenLeaseOfPreviousHandlerExpired_takesEventAgain() {
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));

        event.claim(Instant.parse("2026-09-24T12:15:00Z"));

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.IN_PROGRESS);
        assertThat(event.getAttempts()).isEqualTo(2);
    }

    @Test
    void claim_whenEventAlreadySent_isRejected() {
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));
        event.markSent();

        assertThatThrownBy(() -> event.claim(Instant.parse("2026-09-24T12:15:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
    }

    @Test
    void markSent_whenEventInProgress_finishesDelivery() {
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));

        event.markSent();

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.SENT);
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void markSent_whenEventWasNotTaken_isRejected() {
        assertThatThrownBy(() -> event.markSent()).isInstanceOf(IllegalStateException.class);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
    }

    @Test
    void retryAt_whenAttemptFailed_returnsEventToQueueKeepingAttemptCounted() {
        Instant nextAttempt = Instant.parse("2026-09-24T12:10:00Z");
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));

        event.retryAt(nextAttempt, SMTP_DOWN);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(event.getNextAttemptAt()).isEqualTo(nextAttempt);
        assertThat(event.getAttempts()).isEqualTo(1);
        assertThat(event.getLastErrorCode()).isEqualTo("SMTP_DOWN");
        assertThat(event.getLastErrorMessage()).isEqualTo("Почтовый сервер недоступен");
    }

    @Test
    void markFailed_whenAttemptsExhausted_keepsEventWithReasonAndClosesIt() {
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));

        event.markFailed(SMTP_DOWN);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
        assertThat(event.getLastErrorCode()).isEqualTo("SMTP_DOWN");
        assertThat(event.getAttempts()).isEqualTo(1);
    }

    @Test
    void markFailed_whenEventWasNotTaken_isRejected() {
        assertThatThrownBy(() -> event.markFailed(SMTP_DOWN))
                .isInstanceOf(IllegalStateException.class);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
    }

    @Test
    void claim_whenEventFailedForGood_isRejected() {
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));
        event.markFailed(SMTP_DOWN);

        assertThatThrownBy(() -> event.claim(Instant.parse("2026-09-24T12:15:00Z")))
                .isInstanceOf(IllegalStateException.class);
        assertThat(event.getStatus()).isEqualTo(OutboxStatus.FAILED);
    }

    @Test
    void releaseUnstarted_whenSendingNeverBegan_returnsEventAndCancelsCountedAttempt() {
        Instant now = Instant.parse("2026-09-24T12:00:30Z");
        event.claim(Instant.parse("2026-09-24T12:05:00Z"));

        event.releaseUnstarted(now);

        assertThat(event.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(event.getNextAttemptAt()).isEqualTo(now);
        assertThat(event.getAttempts()).isZero();
    }
}
