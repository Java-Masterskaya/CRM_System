package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Обработчик на реальной базе. Расписание в тестовом профиле выключено — проходы запускаются
 * вручную. «Два экземпляра приложения» — два потока, вызывающие один и тот же обработчик:
 * своего состояния у него нет, согласуются экземпляры только через блокировки в базе.
 */
@Import(OutboxProcessorIntegrationTest.Senders.class)
@TestPropertySource(properties = {
    "app.outbox.batch-size=5",
    "app.outbox.lease=5m",
    "app.outbox.retry-delay=1h"
})
class OutboxProcessorIntegrationTest extends BaseIntegrationTest {

    private static final String DELIVERED = "TEST_DELIVERED";
    private static final String FAILING = "TEST_FAILING";
    private static final String STOLEN = "TEST_STOLEN";

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private RecordingSender recordingSender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        recordingSender.reset();
        tenantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", tenantId);
    }

    @Test
    void processBatch_whenEventDelivered_marksItSentAndNeverSendsItAgain() {
        UUID id = saveEvent(DELIVERED);

        assertThat(processor.processBatch()).isEqualTo(1);
        assertThat(processor.processBatch()).isZero();

        assertThat(recordingSender.sent()).containsExactly(id);
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 1);
    }

    @Test
    void processBatch_whenTwoHandlersRunConcurrently_deliversEveryEventExactlyOnce()
            throws Exception {
        for (int i = 0; i < 40; i++) {
            saveEvent(DELIVERED);
        }
        recordingSender.slowDownBy(Duration.ofMillis(20));
        CountDownLatch start = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> drainAfter(start));
            Future<?> second = executor.submit(() -> drainAfter(start));
            start.countDown();
            first.get(60, TimeUnit.SECONDS);
            second.get(60, TimeUnit.SECONDS);
        }

        assertThat(recordingSender.sent()).hasSize(40).doesNotHaveDuplicates();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE status = 'SENT'", Integer.class))
                .isEqualTo(40);
    }

    @Test
    void processBatch_whenSendingFails_returnsEventToQueueUntilRetryDelayPasses() {
        UUID id = saveEvent(FAILING);

        processor.processBatch();

        assertThat(row(id)).containsEntry("status", "NEW").containsEntry("attempts", 1)
                .containsEntry("waits_at_least_59_minutes", true);
        assertThat(processor.processBatch()).as("до истечения задержки не повторяется").isZero();
    }

    @Test
    void processBatch_whenNoSenderForEventType_countsAttemptAsFailed() {
        UUID id = saveEvent("TEST_NOBODY_SENDS_THIS");

        processor.processBatch();

        assertThat(row(id)).containsEntry("status", "NEW").containsEntry("attempts", 1)
                .containsEntry("waits_at_least_59_minutes", true);
    }

    @Test
    void processBatch_whenPreviousHandlerDiedMidDelivery_deliversEventAfterLeaseExpires() {
        UUID id = saveEvent(DELIVERED);
        jdbcTemplate.update("UPDATE outbox_events SET status = 'IN_PROGRESS', attempts = 1,"
                + " next_attempt_at = now() - interval '1 minute' WHERE id = ?", id);

        processor.processBatch();

        assertThat(recordingSender.sent()).containsExactly(id);
        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 2);
    }

    @Test
    void processBatch_whenStopRequestedMidBatch_finishesCurrentAndReturnsRestToQueue() {
        List<UUID> ids = List.of(saveEvent(DELIVERED), saveEvent(DELIVERED), saveEvent(DELIVERED));

        int processed = processor.processBatch(() -> !recordingSender.sent().isEmpty());

        assertThat(processed).isEqualTo(1);
        UUID sentId = recordingSender.sent().get(0);
        assertThat(row(sentId)).containsEntry("status", "SENT");
        ids.stream().filter(id -> !id.equals(sentId)).forEach(id ->
                assertThat(row(id)).containsEntry("status", "NEW").containsEntry("attempts", 0)
                        .containsEntry("available_now", true));
    }

    @Test
    void processBatch_whenStopAlreadyRequested_takesNothing() {
        UUID id = saveEvent(DELIVERED);

        assertThat(processor.processBatch(() -> true)).isZero();

        assertThat(recordingSender.sent()).isEmpty();
        assertThat(row(id)).containsEntry("status", "NEW").containsEntry("attempts", 0);
    }

    @Test
    void processBatch_whenAnotherHandlerTookEventDuringSending_leavesItsClaimAlone() {
        UUID id = saveEvent(STOLEN);

        processor.processBatch();

        assertThat(row(id)).as("результат не записан поверх чужой аренды")
                .containsEntry("status", "IN_PROGRESS")
                .containsEntry("lease_is_foreign", true);
    }

    private void drainAfter(CountDownLatch start) {
        try {
            start.await();
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return;
        }
        while (processor.processBatch() > 0) {
            // Берём порции, пока готовых событий не останется.
        }
    }

    private UUID saveEvent(String type) {
        return repository.save(new OutboxEvent(tenantId, type, Map.of("n", 1))).getId();
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT status, attempts,"
                + " next_attempt_at >= now() + interval '59 minutes' AS waits_at_least_59_minutes,"
                + " next_attempt_at <= now() AS available_now,"
                + " next_attempt_at = '2100-01-01T00:00:00Z' AS lease_is_foreign"
                + " FROM outbox_events WHERE id = ?", id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Senders {

        @Bean
        RecordingSender recordingSender() {
            return new RecordingSender();
        }

        @Bean
        OutboxEventSender failingSender() {
            return new OutboxEventSender() {
                @Override
                public String eventType() {
                    return FAILING;
                }

                @Override
                public void send(OutboxEvent event) {
                    throw new IllegalStateException("SMTP недоступен для client@example.com");
                }
            };
        }

        /** Пока идёт отправка, событие «забирает» другой обработчик со своей арендой. */
        @Bean
        OutboxEventSender stolenSender(JdbcTemplate jdbcTemplate) {
            return new OutboxEventSender() {
                @Override
                public String eventType() {
                    return STOLEN;
                }

                @Override
                public void send(OutboxEvent event) {
                    jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at ="
                            + " '2100-01-01T00:00:00Z' WHERE id = ?", event.getId());
                }
            };
        }
    }

    static class RecordingSender implements OutboxEventSender {

        private final List<UUID> sent = new CopyOnWriteArrayList<>();
        private volatile Duration delay = Duration.ZERO;

        @Override
        public String eventType() {
            return DELIVERED;
        }

        @Override
        public void send(OutboxEvent event) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            sent.add(event.getId());
        }

        List<UUID> sent() {
            return List.copyOf(sent);
        }

        void slowDownBy(Duration pause) {
            this.delay = pause;
        }

        void reset() {
            sent.clear();
            delay = Duration.ZERO;
        }
    }
}
