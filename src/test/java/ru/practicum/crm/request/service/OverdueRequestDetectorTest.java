package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import ru.practicum.crm.request.config.OverdueDetectionProperties;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Проход проверки без базы: репозиторий — заглушка, которая возвращает, сколько заявок
 * помечено в порции. Сами условия просрочки и блокировки проверяет интеграционный тест.
 */
class OverdueRequestDetectorTest {

    private static final int BATCH = 2;

    private final RequestRepository repository = mock(RequestRepository.class);
    private final OverdueRequestDetector detector = new OverdueRequestDetector(repository,
            mock(PlatformTransactionManager.class),
            new OverdueDetectionProperties(BATCH, Duration.ofMinutes(1)));

    @Test
    void detect_marksBatchAfterBatchWhileBatchesAreFull() {
        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(2, 2, 1);
        when(repository.markResolutionOverdue(any(), eq(BATCH))).thenReturn(0);

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
        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(2, 0);
        when(repository.markResolutionOverdue(any(), eq(BATCH))).thenReturn(1);
        OverdueRequestDetector withTickingClock = new OverdueRequestDetector(repository,
                mock(PlatformTransactionManager.class),
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
        when(repository.markFirstResponseOverdue(any(), eq(BATCH))).thenReturn(2);
        AtomicInteger checks = new AtomicInteger();

        assertThat(detector.detect(() -> checks.incrementAndGet() > 1)).isEqualTo(2);

        verify(repository, times(1)).markFirstResponseOverdue(any(), eq(BATCH));
        verify(repository, times(0)).markResolutionOverdue(any(), eq(BATCH));
    }

    @Test
    void detect_whenStoppedBeforeStart_touchesNothing() {
        assertThat(detector.detect(() -> true)).isZero();

        verifyNoInteractions(repository);
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

        verifyNoInteractions(repository);
    }
}
