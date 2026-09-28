package ru.practicum.crm.outbox.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.TimeGauge;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import ru.practicum.crm.outbox.domain.OutboxStatus;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Метрики очереди исходящих событий (T-073): исходы попыток доставки, число событий по
 * состояниям и возраст самого давнего недоставленного события.
 *
 * <p>Показатели очереди читаются из базы в момент, когда их запрашивает сборщик метрик, и
 * охватывают всех арендаторов: это служебная картина очереди, она доступна только на служебном
 * порту. Доставленные события не считаются: до очистки очереди их число растёт без предела, а
 * сколько доставлено, видно по счётчику успешных попыток.
 *
 * <p>В метках — только тип события, исход и код причины: ни адресов, ни содержимого.
 */
@Component
public class OutboxMetrics {

    static final String ATTEMPTS = "crm.outbox.delivery.attempts";
    static final String EVENTS = "crm.outbox.events";
    static final String OLDEST_PENDING_AGE = "crm.outbox.oldest.pending.age";

    /** Значение метки причины у успешной попытки — как {@code exception="none"} у HTTP-метрик. */
    static final String NO_REASON = "none";

    private static final List<OutboxStatus> COUNTED =
            List.of(OutboxStatus.NEW, OutboxStatus.IN_PROGRESS, OutboxStatus.FAILED);
    private static final List<OutboxStatus> PENDING =
            List.of(OutboxStatus.NEW, OutboxStatus.IN_PROGRESS);

    private final Meter.MeterProvider<Counter> attempts;

    public OutboxMetrics(MeterRegistry registry, OutboxEventRepository repository) {
        this.attempts = Counter.builder(ATTEMPTS)
                .description("Попытки доставки исходящих событий по исходу")
                .withRegistry(registry);
        for (OutboxStatus status : COUNTED) {
            Gauge.builder(EVENTS, repository, events -> events.countByStatus(status))
                    .description("Исходящие события в этом состоянии")
                    .tag("status", status.name())
                    .register(registry);
        }
        TimeGauge.builder(OLDEST_PENDING_AGE, repository, TimeUnit.SECONDS,
                        OutboxMetrics::oldestPendingAgeSeconds)
                .description("Сколько ждёт доставки самое давнее недоставленное событие")
                .register(registry);
    }

    /** Попытка удалась. */
    void delivered(String eventType) {
        count(eventType, "success", NO_REASON);
    }

    /** Попытка не удалась, событие вернулось в очередь. */
    void willRetry(String eventType, String reason) {
        count(eventType, "retry", reason);
    }

    /** Попытки исчерпаны: окончательный неуспех. */
    void failed(String eventType, String reason) {
        count(eventType, "failed", reason);
    }

    private void count(String eventType, String outcome, String reason) {
        attempts.withTags("event_type", eventType, "outcome", outcome, "reason", reason)
                .increment();
    }

    private static double oldestPendingAgeSeconds(OutboxEventRepository repository) {
        return repository.findOldestCreatedAt(PENDING)
                .map(oldest -> Duration.between(oldest, Instant.now()).toMillis() / 1000.0)
                .map(seconds -> Math.max(0, seconds))
                .orElse(0.0);
    }
}
