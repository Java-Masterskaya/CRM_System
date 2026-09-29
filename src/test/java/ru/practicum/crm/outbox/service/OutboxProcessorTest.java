package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import ru.practicum.crm.outbox.api.OutboxEventSender;
import ru.practicum.crm.outbox.api.OutboxMessage;
import ru.practicum.crm.outbox.config.OutboxProperties;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.domain.OutboxStatus;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Метрики обработчика без базы (#154): когда счётчик попыток увеличивается и какие ряды есть с
 * запуска. Транзакции подменены: так можно сорвать фиксацию записи результата, что на реальной
 * базе воспроизвести трудно.
 */
class OutboxProcessorTest {

    private static final String DELIVERED = "TEST_DELIVERED";
    private static final String FAILING = "TEST_FAILING";

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final OutboxEventRepository repository = mock(OutboxEventRepository.class);
    private final PlatformTransactionManager transactionManager =
            mock(PlatformTransactionManager.class);
    private final OutboxProperties properties = new OutboxProperties(10, Duration.ofSeconds(5),
            Duration.ofMinutes(5), Duration.ofMinutes(1), Duration.ofHours(2), 0, 8);

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    }

    @Test
    void constructor_registersZeroSuccessSeriesForEverySender() {
        processor(sender(DELIVERED), failingSender());

        assertThat(counter(DELIVERED, "success", OutboxMetrics.NO_REASON)).isNotNull()
                .extracting(Counter::count).isEqualTo(0.0);
        assertThat(counter(FAILING, "success", OutboxMetrics.NO_REASON))
                .as("ряд есть, даже если успешных попыток у этого типа ещё не было")
                .isNotNull();
    }

    @Test
    void processBatch_whenResultCommits_countsAttemptOnce() {
        OutboxProcessor processor = processor(sender(DELIVERED));
        givenReadyEvent(DELIVERED);

        processor.processBatch();

        assertThat(count(DELIVERED, "success", OutboxMetrics.NO_REASON)).isEqualTo(1.0);
    }

    @Test
    void processBatch_whenSuccessCannotBeCommitted_doesNotCountAttempt() {
        OutboxProcessor processor = processor(sender(DELIVERED));
        givenReadyEvent(DELIVERED);
        failSecondCommit();

        assertThatThrownBy(() -> processor.processBatch())
                .isInstanceOf(TransactionSystemException.class);

        assertThat(count(DELIVERED, "success", OutboxMetrics.NO_REASON)).isZero();
    }

    @Test
    void processBatch_whenRetryCannotBeCommitted_doesNotCountAttempt() {
        OutboxProcessor processor = processor(failingSender());
        givenReadyEvent(FAILING);
        failSecondCommit();

        assertThatThrownBy(() -> processor.processBatch())
                .isInstanceOf(TransactionSystemException.class);

        assertThat(count(FAILING, "retry", OutboxProcessor.UNEXPECTED_ERROR)).isZero();
    }

    /** Захват порции фиксируется, а запись результата попытки — нет. */
    private void failSecondCommit() {
        doNothing().doThrow(new TransactionSystemException("Фиксация не удалась"))
                .when(transactionManager).commit(any());
    }

    /**
     * Одно событие готово к отправке. Идентификатор у несохранённого события пуст, поэтому
     * поиск взятого события отвечает на любой.
     */
    private void givenReadyEvent(String eventType) {
        OutboxEvent event = new OutboxEvent(UUID.randomUUID(), eventType, Map.of());
        when(repository.lockReadyBatch(any(), anyInt())).thenReturn(List.of(event));
        when(repository.findByIdAndStatus(any(), eq(OutboxStatus.IN_PROGRESS)))
                .thenReturn(Optional.of(event));
    }

    @SuppressWarnings("unchecked")
    private OutboxProcessor processor(OutboxEventSender... senders) {
        ObjectProvider<OutboxEventSender> provider = mock(ObjectProvider.class);
        when(provider.orderedStream()).thenReturn(Stream.of(senders));
        return new OutboxProcessor(repository, transactionManager, properties, provider,
                new OutboxMetrics(registry, repository));
    }

    private static OutboxEventSender sender(String eventType) {
        return new OutboxEventSender() {
            @Override
            public String eventType() {
                return eventType;
            }

            @Override
            public void send(OutboxMessage message) {
                // Доставка удаётся.
            }
        };
    }

    private static OutboxEventSender failingSender() {
        return new OutboxEventSender() {
            @Override
            public String eventType() {
                return FAILING;
            }

            @Override
            public void send(OutboxMessage message) {
                throw new IllegalStateException("Внешний сервис недоступен");
            }
        };
    }

    private Counter counter(String eventType, String outcome, String reason) {
        return registry.find(OutboxMetrics.ATTEMPTS)
                .tags("event_type", eventType, "outcome", outcome, "reason", reason)
                .counter();
    }

    private double count(String eventType, String outcome, String reason) {
        Counter counter = counter(eventType, outcome, reason);
        return counter == null ? 0 : counter.count();
    }
}
