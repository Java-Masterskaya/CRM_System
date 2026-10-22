package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.transaction.PlatformTransactionManager;
import ru.practicum.crm.request.config.OverdueDetectionProperties;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.service.OverdueEscalation.Deadline;

/**
 * Проход проверки без базы: репозиторий и эскалация — заглушки, которые возвращают, сколько
 * заявок обработано в порции. Сами условия просрочки и блокировки проверяет интеграционный тест.
 */
class OverdueRequestDetectorTest {

    private static final int BATCH = 2;

    private final RequestRepository repository = mock(RequestRepository.class);
    private final OverdueEscalation escalation = mock(OverdueEscalation.class);
    private final RequestSlaMetrics slaMetrics = mock(RequestSlaMetrics.class);
    private final OverdueRequestDetector detector = new OverdueRequestDetector(repository,
            escalation, slaMetrics, mock(PlatformTransactionManager.class),
            new OverdueDetectionProperties(BATCH, Duration.ofMinutes(1)));

    @Test
    void detect_marksBatchAfterBatchWhileBatchesAreFull() {
        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(
                tenants(2), tenants(2), tenants(1));
        when(repository.markResolutionOverdue(any(), eq(BATCH))).thenReturn(List.of());

        assertThat(detector.detect(() -> false)).isEqualTo(5);

        verify(repository, times(3)).markFirstResponseOverdue(any(), eq(BATCH));
        verify(repository, times(1)).markResolutionOverdue(any(), eq(BATCH));
    }

    /**
     * Одна точка отсчёта на проход: заявки, просрочившиеся по ходу, достанутся следующему.
     * Часы сдвигаются на секунду при каждом обращении — если бы порции брали момент сами,
     * моменты разошлись бы.
     */
    @Test
    void detect_checksAllBatchesAgainstOneMoment() {
        when(repository.markFirstResponseOverdue(any(), eq(BATCH)))
                .thenReturn(tenants(2), List.of());
        when(repository.markResolutionOverdue(any(), eq(BATCH))).thenReturn(tenants(1));
        OverdueRequestDetector withTickingClock = new OverdueRequestDetector(repository,
                escalation, slaMetrics, mock(PlatformTransactionManager.class),
                new OverdueDetectionProperties(BATCH, Duration.ofMinutes(1)), tickingClock());

        withTickingClock.detect(() -> false);

        ArgumentCaptor<Instant> firstResponse = ArgumentCaptor.forClass(Instant.class);
        ArgumentCaptor<Instant> resolution = ArgumentCaptor.forClass(Instant.class);
        verify(repository, times(2)).markFirstResponseOverdue(firstResponse.capture(), eq(BATCH));
        verify(repository).markResolutionOverdue(resolution.capture(), eq(BATCH));
        assertThat(firstResponse.getAllValues()).containsOnly(resolution.getValue());
    }

    /** Остановка приложения: начатая порция дописана, следующие не начинаются. */
    @Test
    void detect_whenStopRequestedAfterFirstBatch_startsNoMoreBatches() {
        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(tenants(2));
        AtomicInteger checks = new AtomicInteger();

        assertThat(detector.detect(() -> checks.incrementAndGet() > 1)).isEqualTo(2);

        verify(repository, times(1)).markFirstResponseOverdue(any(), eq(BATCH));
        verify(repository, times(0)).markResolutionOverdue(any(), eq(BATCH));
        verifyNoInteractions(escalation);
    }

    @Test
    void detect_whenStoppedBeforeStart_touchesNothing() {
        assertThat(detector.detect(() -> true)).isZero();

        verifyNoInteractions(repository, escalation);
    }

    /**
     * Эскалация — после пометки: письма о только что просроченных заявках уходят в том же
     * проходе. По каждому сроку — порция за порцией, пока порция полная; на результат прохода
     * эскалация не влияет — он по-прежнему считает пометки.
     */
    @Test
    void detect_afterMarking_escalatesEachDeadlineBatchAfterBatch() {
        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(tenants(1));
        when(repository.markResolutionOverdue(any(), eq(BATCH))).thenReturn(List.of());
        when(escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH)).thenReturn(2, 1);
        when(escalation.escalateBatch(Deadline.RESOLUTION, BATCH)).thenReturn(0);

        assertThat(detector.detect(() -> false)).isEqualTo(1);

        InOrder order = inOrder(repository, escalation);
        order.verify(repository).markFirstResponseOverdue(any(), eq(BATCH));
        order.verify(repository).markResolutionOverdue(any(), eq(BATCH));
        order.verify(escalation, times(2)).escalateBatch(Deadline.FIRST_RESPONSE, BATCH);
        order.verify(escalation).escalateBatch(Deadline.RESOLUTION, BATCH);
    }

    /** Остановка посреди эскалации: начатая порция дописана, следующие не начинаются. */
    @Test
    void detect_whenStopRequestedDuringEscalation_startsNoMoreBatches() {
        when(escalation.escalateBatch(Deadline.FIRST_RESPONSE, BATCH)).thenReturn(2);
        AtomicInteger checks = new AtomicInteger();

        detector.detect(() -> checks.incrementAndGet() > 3);

        verify(escalation, times(1)).escalateBatch(Deadline.FIRST_RESPONSE, BATCH);
        verify(escalation, times(0)).escalateBatch(Deadline.RESOLUTION, BATCH);
    }

    /** Часы, которые при каждом обращении показывают на секунду больше. */
    private static Clock tickingClock() {
        AtomicLong seconds = new AtomicLong();
        return new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneOffset.UTC;
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return Instant.EPOCH.plusSeconds(seconds.incrementAndGet());
            }
        };
    }

    /** Приложение закрывается: проход по расписанию после этого ничего не трогает. */
    @Test
    void detect_afterApplicationShutdown_touchesNothing() {
        detector.onApplicationShutdown();

        assertThat(detector.detect()).isZero();

        verifyNoInteractions(repository, escalation, slaMetrics);
    }

    /** Каждое нарушение увеличивает счетчик своего срока и своего арендатора. */
    @Test
    void detect_whenDeadlinesMissed_countsEachBreachForItsTenant() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(List.of(first));
        when(repository.markResolutionOverdue(any(), eq(BATCH)))
                .thenReturn(List.of(first, second), List.of());

        assertThat(detector.detect(() -> false)).isEqualTo(3);

        verify(slaMetrics).firstResponseBreached(first);
        verify(slaMetrics).resolutionBreached(first);
        verify(slaMetrics).resolutionBreached(second);
        verify(slaMetrics, times(1)).firstResponseBreached(any());
        verify(slaMetrics, times(2)).resolutionBreached(any());
    }

    private static List<UUID> tenants(int count) {
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }
}
