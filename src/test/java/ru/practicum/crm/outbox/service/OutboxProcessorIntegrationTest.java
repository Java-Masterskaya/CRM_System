package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.api.OutboxDeliveryException;
import ru.practicum.crm.outbox.api.OutboxEventSender;
import ru.practicum.crm.outbox.api.OutboxMessage;
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
    "app.outbox.retry-delay=1h",
    "app.outbox.max-retry-delay=10h",
    "app.outbox.max-attempts=4"
})
class OutboxProcessorIntegrationTest extends BaseIntegrationTest {

    private static final String DELIVERED = "TEST_DELIVERED";
    private static final String FAILING = "TEST_FAILING";
    private static final String STOLEN = "TEST_STOLEN";
    private static final String FLAKY = "TEST_FLAKY";
    private static final String KNOWN_FAILURE = "TEST_KNOWN_FAILURE";

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private RecordingSender recordingSender;

    @Autowired
    private FlakySender flakySender;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        recordingSender.reset();
        flakySender.failNextCalls(0);
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
    void processBatch_whenEventDelivered_givesSenderEventDataAndAttemptNumber() {
        UUID id = repository.save(new OutboxEvent(tenantId, DELIVERED,
                Map.of("recipient", "user-1"))).getId();
        jdbcTemplate.update("UPDATE outbox_events SET attempts = 2 WHERE id = ?", id);

        processor.processBatch();

        assertThat(recordingSender.received()).singleElement().satisfies(message -> {
            assertThat(message.id()).isEqualTo(id);
            assertThat(message.tenantId()).isEqualTo(tenantId);
            assertThat(message.eventType()).isEqualTo(DELIVERED);
            assertThat(message.payload()).containsExactly(Map.entry("recipient", "user-1"));
            assertThat(message.attempt()).as("третья попытка").isEqualTo(3);
        });
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
                .containsEntry("waits_at_least_59_minutes", true)
                .containsEntry("last_error_code", OutboxProcessor.UNEXPECTED_ERROR)
                .containsEntry("last_error_message", "IllegalStateException");
        assertThat(processor.processBatch()).as("до истечения задержки не повторяется").isZero();
    }

    @Test
    void processBatch_whenNoSenderForEventType_countsAttemptAsFailed() {
        UUID id = saveEvent("TEST_NOBODY_SENDS_THIS");

        processor.processBatch();

        assertThat(row(id)).containsEntry("status", "NEW").containsEntry("attempts", 1)
                .containsEntry("waits_at_least_59_minutes", true)
                .containsEntry("last_error_code", OutboxProcessor.NO_SENDER);
    }

    @Test
    void processBatch_whenFailuresRepeat_waitsLongerEachTimeAndGivesUpAfterLimit() {
        UUID id = saveEvent(FAILING);
        long[] delays = new long[3];

        for (int attempt = 1; attempt <= 3; attempt++) {
            processor.processBatch();
            Map<String, Object> state = row(id);
            assertThat(state).containsEntry("status", "NEW").containsEntry("attempts", attempt);
            delays[attempt - 1] = ((Number) state.get("delay_seconds")).longValue();
            assertThat(processor.processBatch())
                    .as("после попытки %d раньше задержки не повторяется", attempt).isZero();
            makeDue(id);
        }
        processor.processBatch();

        assertThat(delays[0]).as("1 час").isBetween(3_500L, 3_600L);
        assertThat(delays[1]).as("2 часа").isBetween(7_100L, 7_200L);
        assertThat(delays[2]).as("4 часа").isBetween(14_300L, 14_400L);
        assertThat(row(id)).containsEntry("status", "FAILED").containsEntry("attempts", 4)
                .containsEntry("last_error_code", OutboxProcessor.UNEXPECTED_ERROR);
        makeDue(id);
        assertThat(processor.processBatch()).as("окончательный неуспех не выбирается").isZero();
    }

    @Test
    void processBatch_whenServiceRecoversBeforeLimit_deliversEvent() {
        UUID id = saveEvent(FLAKY);
        flakySender.failNextCalls(2);

        processor.processBatch();
        makeDue(id);
        processor.processBatch();
        makeDue(id);
        processor.processBatch();

        assertThat(row(id)).containsEntry("status", "SENT").containsEntry("attempts", 3);
    }

    @Test
    void processBatch_whenSenderReportsKnownFailure_keepsItsCodeAndMessage() {
        UUID id = saveEvent(KNOWN_FAILURE);

        processor.processBatch();

        assertThat(row(id)).containsEntry("last_error_code", "SMTP_UNAVAILABLE")
                .containsEntry("last_error_message", "Почтовый сервер недоступен");
    }

    @Test
    void processBatch_whenDeliveryFails_logsNeitherAddressesNorLetterText() {
        UUID id = repository.save(new OutboxEvent(tenantId, FAILING,
                Map.of("email", "client@example.com", "text", "Текст письма"))).getId();
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            processor.processBatch();
            jdbcTemplate.update("UPDATE outbox_events SET attempts = 3 WHERE id = ?", id);
            makeDue(id);
            processor.processBatch();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(message -> message.contains("окончательно не доставлено"))
                .allSatisfy(message -> assertThat(message)
                        .doesNotContain("client@example.com", "Текст письма"));
        assertThat(appender.list).allSatisfy(event ->
                assertThat(event.getThrowableProxy()).isNull());
        assertThat(row(id)).containsEntry("status", "FAILED");
        assertThat((String) row(id).get("last_error_message"))
                .doesNotContain("client@example.com");
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
                + " last_error_code, last_error_message,"
                + " extract(epoch FROM next_attempt_at - now())::bigint AS delay_seconds,"
                + " next_attempt_at >= now() + interval '59 minutes' AS waits_at_least_59_minutes,"
                + " next_attempt_at <= now() AS available_now,"
                + " next_attempt_at = '2100-01-01T00:00:00Z' AS lease_is_foreign"
                + " FROM outbox_events WHERE id = ?", id);
    }

    /** Имитирует течение времени: событие становится доступным для следующей попытки. */
    private void makeDue(UUID id) {
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'"
                + " WHERE id = ?", id);
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
                public void send(OutboxMessage message) {
                    throw new IllegalStateException("SMTP недоступен для client@example.com");
                }
            };
        }

        @Bean
        FlakySender flakySender() {
            return new FlakySender();
        }

        @Bean
        OutboxEventSender knownFailureSender() {
            return new OutboxEventSender() {
                @Override
                public String eventType() {
                    return KNOWN_FAILURE;
                }

                @Override
                public void send(OutboxMessage message) {
                    throw new OutboxDeliveryException("SMTP_UNAVAILABLE",
                            "Почтовый сервер недоступен");
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
                public void send(OutboxMessage message) {
                    jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at ="
                            + " '2100-01-01T00:00:00Z' WHERE id = ?", message.id());
                }
            };
        }
    }

    /** Падает заданное число раз подряд, потом доставляет — как восстановившийся сервис. */
    static class FlakySender implements OutboxEventSender {

        private final AtomicInteger failuresLeft = new AtomicInteger();

        @Override
        public String eventType() {
            return FLAKY;
        }

        @Override
        public void send(OutboxMessage message) {
            if (failuresLeft.getAndUpdate(left -> Math.max(0, left - 1)) > 0) {
                throw new OutboxDeliveryException("SMTP_UNAVAILABLE",
                        "Почтовый сервер недоступен");
            }
        }

        void failNextCalls(int count) {
            failuresLeft.set(count);
        }
    }

    static class RecordingSender implements OutboxEventSender {

        private final List<OutboxMessage> received = new CopyOnWriteArrayList<>();
        private volatile Duration delay = Duration.ZERO;

        @Override
        public String eventType() {
            return DELIVERED;
        }

        @Override
        public void send(OutboxMessage message) {
            try {
                Thread.sleep(delay.toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
            received.add(message);
        }

        List<UUID> sent() {
            return received.stream().map(OutboxMessage::id).toList();
        }

        List<OutboxMessage> received() {
            return List.copyOf(received);
        }

        void slowDownBy(Duration pause) {
            this.delay = pause;
        }

        void reset() {
            received.clear();
            delay = Duration.ZERO;
        }
    }
}
