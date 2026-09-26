package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import net.logstash.logback.encoder.LogstashEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalManagementPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Метрики и логи очереди на реальной базе (T-073). Тестовые отправители — те же, что в
 * {@link OutboxProcessorIntegrationTest}; типы событий повторены строками.
 *
 * <p>{@link AutoConfigureObservability} включает экспорт метрик, который в тестах Spring Boot по
 * умолчанию выключен, — без него служебного эндпоинта {@code /actuator/prometheus} нет.
 */
@Import(OutboxProcessorIntegrationTest.Senders.class)
@AutoConfigureObservability
@TestPropertySource(properties = {
    "app.outbox.retry-delay=1h",
    "app.outbox.max-retry-delay=10h",
    "app.outbox.max-attempts=3"
})
class OutboxMetricsIntegrationTest extends BaseIntegrationTest {

    private static final String DELIVERED = "TEST_DELIVERED";
    private static final String FAILING = "TEST_FAILING";
    private static final String FLAKY = "TEST_FLAKY";

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private OutboxProcessorIntegrationTest.FlakySender flakySender;

    @Autowired
    private MeterRegistry registry;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate rest;

    @LocalManagementPort
    private int managementPort;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        flakySender.failNextCalls(0);
        tenantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", tenantId);
    }

    @Test
    void metrics_whenDeliveryFailsThenRecovers_showFailuresAndWaitingThenReturnToNormal() {
        flakySender.failNextCalls(2);
        UUID id = saveEvent(FLAKY);
        jdbcTemplate.update("UPDATE outbox_events SET created_at = now() - interval '10 minutes'"
                + " WHERE id = ?", id);
        final double retriesBefore = attempts(FLAKY, "retry", "SMTP_UNAVAILABLE");
        final double successesBefore = attempts(FLAKY, "success", OutboxMetrics.NO_REASON);

        processor.processBatch();
        makeDue(id);
        processor.processBatch();

        assertThat(attempts(FLAKY, "retry", "SMTP_UNAVAILABLE") - retriesBefore).isEqualTo(2);
        assertThat(events("NEW")).isEqualTo(1);
        assertThat(oldestPendingAgeSeconds()).as("ждёт с момента создания").isGreaterThan(590);

        makeDue(id);
        processor.processBatch();

        assertThat(attempts(FLAKY, "success", OutboxMetrics.NO_REASON) - successesBefore)
                .isEqualTo(1);
        assertThat(events("NEW")).isZero();
        assertThat(events("IN_PROGRESS")).isZero();
        assertThat(oldestPendingAgeSeconds()).isZero();
    }

    @Test
    void processBatch_whenAttemptsRunOut_countsFinalFailureAndLogsStructuredRecord()
            throws IOException {
        UUID id = saveEvent(FAILING);
        jdbcTemplate.update("UPDATE outbox_events SET attempts = 2 WHERE id = ?", id);
        final double failuresBefore = attempts(FAILING, "failed", "UNEXPECTED_ERROR");
        Logger logger = (Logger) LoggerFactory.getLogger(OutboxProcessor.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            processor.processBatch();
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(attempts(FAILING, "failed", "UNEXPECTED_ERROR") - failuresBefore).isEqualTo(1);
        assertThat(events("FAILED")).isEqualTo(1);
        ILoggingEvent record = appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("окончательно"))
                .findFirst().orElseThrow();
        JsonNode json = asJson(record, logger.getLoggerContext());
        assertThat(json.get("eventId").asText()).isEqualTo(id.toString());
        assertThat(json.get("eventType").asText()).isEqualTo(FAILING);
        assertThat(json.get("attempts").asInt()).isEqualTo(3);
        assertThat(json.get("errorCode").asText()).isEqualTo("UNEXPECTED_ERROR");
        assertThat(json.get("message").asText()).as("текст сообщения прежний")
                .contains(id.toString()).doesNotContain("eventId=");
    }

    @Test
    void prometheusEndpoint_afterDelivery_exposesQueueMetrics() {
        saveEvent(DELIVERED);
        processor.processBatch();

        String metrics = rest.getForObject(
                "http://localhost:" + managementPort + "/actuator/prometheus", String.class);

        assertThat(metrics)
                .contains("crm_outbox_delivery_attempts_total{")
                .contains("outcome=\"success\"")
                .contains("crm_outbox_events{")
                .contains("status=\"FAILED\"")
                .contains("crm_outbox_oldest_pending_age_seconds");
    }

    private UUID saveEvent(String type) {
        return repository.save(new OutboxEvent(tenantId, type, Map.of("n", 1))).getId();
    }

    /** Имитирует течение времени: событие становится доступным для следующей попытки. */
    private void makeDue(UUID id) {
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() - interval '1 second'"
                + " WHERE id = ?", id);
    }

    private double attempts(String eventType, String outcome, String reason) {
        Counter counter = registry.find(OutboxMetrics.ATTEMPTS)
                .tags("event_type", eventType, "outcome", outcome, "reason", reason)
                .counter();
        return counter == null ? 0 : counter.count();
    }

    private double events(String status) {
        return registry.get(OutboxMetrics.EVENTS).tag("status", status).gauge().value();
    }

    private double oldestPendingAgeSeconds() {
        return registry.get(OutboxMetrics.OLDEST_PENDING_AGE).timeGauge()
                .value(TimeUnit.SECONDS);
    }

    /** Запись лога в том виде, в каком её выводит приложение (JSON). */
    private static JsonNode asJson(ILoggingEvent record, LoggerContext context)
            throws IOException {
        LogstashEncoder encoder = new LogstashEncoder();
        encoder.setContext(context);
        encoder.start();
        try {
            return new ObjectMapper().readTree(encoder.encode(record));
        } finally {
            encoder.stop();
        }
    }
}
