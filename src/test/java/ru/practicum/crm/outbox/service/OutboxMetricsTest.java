package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.outbox.domain.OutboxStatus;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

class OutboxMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final OutboxMetrics metrics = new OutboxMetrics(registry, repository);

    @Test
    void delivered_countsSuccessWithoutReason() {
        metrics.delivered("EMAIL_NOTIFICATION");
        metrics.delivered("EMAIL_NOTIFICATION");

        assertThat(attempts("EMAIL_NOTIFICATION", "success", OutboxMetrics.NO_REASON))
                .isEqualTo(2.0);
    }

    @Test
    void failures_areCountedByOutcomeAndReason() {
        metrics.willRetry("EMAIL_NOTIFICATION", "MAIL_SERVER_UNAVAILABLE");
        metrics.willRetry("EMAIL_NOTIFICATION", "MAIL_SERVER_UNAVAILABLE");
        metrics.failed("EMAIL_NOTIFICATION", "MAIL_REJECTED");

        assertThat(attempts("EMAIL_NOTIFICATION", "retry", "MAIL_SERVER_UNAVAILABLE"))
                .isEqualTo(2.0);
        assertThat(attempts("EMAIL_NOTIFICATION", "failed", "MAIL_REJECTED")).isEqualTo(1.0);
    }

    @Test
    void eventsGauge_showsQueueByStatusWithoutDeliveredEvents() {
        when(repository.countByStatus(OutboxStatus.NEW)).thenReturn(3L);
        when(repository.countByStatus(OutboxStatus.FAILED)).thenReturn(1L);

        assertThat(events(OutboxStatus.NEW)).isEqualTo(3.0);
        assertThat(events(OutboxStatus.IN_PROGRESS)).isZero();
        assertThat(events(OutboxStatus.FAILED)).isEqualTo(1.0);
        assertThat(registry.find(OutboxMetrics.EVENTS).tag("status", "SENT").gauge()).isNull();
    }

    @Test
    void oldestPendingAge_whenEventWaits_isItsAgeInSecondsAmongUndeliveredOnly() {
        when(repository.findOldestCreatedAt(anyCollection()))
                .thenReturn(Optional.of(Instant.now().minusSeconds(120)));

        assertThat(oldestPendingAge()).isBetween(119.0, 130.0);
        verify(repository).findOldestCreatedAt(
                List.of(OutboxStatus.NEW, OutboxStatus.IN_PROGRESS));
    }

    @Test
    void oldestPendingAge_whenNothingWaits_isZero() {
        when(repository.findOldestCreatedAt(anyCollection())).thenReturn(Optional.empty());

        assertThat(oldestPendingAge()).isZero();
    }

    private double attempts(String eventType, String outcome, String reason) {
        Counter counter = registry.find(OutboxMetrics.ATTEMPTS)
                .tags("event_type", eventType, "outcome", outcome, "reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double events(OutboxStatus status) {
        return registry.get(OutboxMetrics.EVENTS).tag("status", status.name()).gauge().value();
    }

    private double oldestPendingAge() {
        return registry.get(OutboxMetrics.OLDEST_PENDING_AGE).timeGauge()
                .value(TimeUnit.SECONDS);
    }
}
