package ru.practicum.crm.security.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.PlatformTransactionManager;
import ru.practicum.crm.security.config.RefreshTokenCleanupProperties;
import ru.practicum.crm.security.repository.RefreshTokenRepository;

/**
 * Проход очистки без базы: репозиторий — заглушка, которая возвращает, сколько записей удалено
 * в порции. Сами условия удаления, блокировки и планы запросов проверяет интеграционный тест.
 */
class RefreshTokenCleanerTest {

    private static final int BATCH = 2;

    private final RefreshTokenRepository repository = mock(RefreshTokenRepository.class);
    private final RefreshTokenCleaner cleaner = cleanerWith(Clock.systemUTC());

    @Test
    void cleanUp_purgesBatchAfterBatchWhileBatchesAreFull() {
        when(repository.purgeExpired(any(), eq(BATCH))).thenReturn(2, 2, 1);
        when(repository.purgeRevoked(BATCH)).thenReturn(2, 0);

        assertThat(cleaner.cleanUp(() -> false)).isEqualTo(7);

        verify(repository, times(3)).purgeExpired(any(), eq(BATCH));
        verify(repository, times(2)).purgeRevoked(BATCH);
    }

    /**
     * Один момент отсчёта на проход: токены, истёкшие по ходу, достанутся следующему. Часы
     * сдвигаются на секунду при каждом обращении — если бы порции брали момент сами, моменты
     * разошлись бы.
     */
    @Test
    void cleanUp_comparesAllBatchesWithOneMoment() {
        when(repository.purgeExpired(any(), eq(BATCH))).thenReturn(2, 2, 0);
        RefreshTokenCleaner withTickingClock = cleanerWith(tickingClock());

        withTickingClock.cleanUp(() -> false);

        ArgumentCaptor<OffsetDateTime> moments = ArgumentCaptor.forClass(OffsetDateTime.class);
        verify(repository, times(3)).purgeExpired(moments.capture(), eq(BATCH));
        assertThat(moments.getAllValues()).containsOnly(moments.getValue());
    }

    /** Остановка приложения: начатая порция дописана, следующие не начинаются. */
    @Test
    void cleanUp_whenStopRequestedAfterFirstBatch_startsNoMoreBatches() {
        when(repository.purgeExpired(any(), eq(BATCH))).thenReturn(2);
        AtomicInteger checks = new AtomicInteger();

        assertThat(cleaner.cleanUp(() -> checks.incrementAndGet() > 1)).isEqualTo(2);

        verify(repository, times(1)).purgeExpired(any(), eq(BATCH));
        verify(repository, never()).purgeRevoked(BATCH);
    }

    /** Приложение закрывается: проход по расписанию после этого ничего не трогает. */
    @Test
    void cleanUp_afterApplicationShutdown_touchesNothing() {
        cleaner.onApplicationShutdown();

        assertThat(cleaner.cleanUp()).isZero();

        verifyNoInteractions(repository);
    }

    private RefreshTokenCleaner cleanerWith(Clock clock) {
        return new RefreshTokenCleaner(repository, mock(PlatformTransactionManager.class),
                new RefreshTokenCleanupProperties(BATCH, Duration.ofHours(1)), clock);
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
}
