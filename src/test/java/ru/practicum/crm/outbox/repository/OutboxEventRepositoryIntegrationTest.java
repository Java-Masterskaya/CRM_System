package ru.practicum.crm.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.domain.OutboxStatus;

class OutboxEventRepositoryIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202609221040";

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void createTenants() {
        tenantA = createTenant("Арендатор А");
        tenantB = createTenant("Арендатор Б");
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        Boolean success = jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION);

        assertThat(success).isTrue();
    }

    @Test
    void save_whenEventStored_readsBackEveryField() {
        OutboxEvent event = new OutboxEvent(tenantA, "REQUEST_CREATED",
                Map.of("requestId", "42", "status", "NEW"));

        UUID id = repository.save(event).getId();
        OutboxEvent saved = repository.findByIdAndTenantId(id, tenantA).orElseThrow();

        assertThat(saved.getTenantId()).isEqualTo(tenantA);
        assertThat(saved.getEventType()).isEqualTo("REQUEST_CREATED");
        assertThat(saved.getPayload()).containsEntry("requestId", "42")
                .containsEntry("status", "NEW");
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.NEW);
        assertThat(saved.getAttempts()).isZero();
        assertThat(saved.getNextAttemptAt()).isEqualTo(saved.getCreatedAt());
        assertThat(saved.getUpdatedAt()).isNotNull();
    }

    @Test
    void findById_whenEventBelongsToAnotherTenant_returnsNothing() {
        UUID id = repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED", Map.of())).getId();

        assertThat(repository.findByIdAndTenantId(id, tenantB)).isEmpty();
        assertThat(repository.findByIdAndTenantId(id, tenantA)).isPresent();
    }

    @Test
    void findByTenant_whenAnotherTenantHasEvents_returnsOnlyOwn() {
        repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED", Map.of("n", 1)));
        repository.save(new OutboxEvent(tenantB, "REQUEST_CREATED", Map.of("n", 2)));

        assertThat(repository.findByTenantIdOrderByCreatedAtDesc(tenantA, PageRequest.of(0, 10)))
                .singleElement()
                .extracting(OutboxEvent::getTenantId)
                .isEqualTo(tenantA);
        assertThat(repository.countByTenantIdAndStatus(tenantA, OutboxStatus.NEW)).isOne();
    }

    @Test
    void findReadyToSend_whenAttemptTimeIsInFuture_skipsEvent() {
        UUID id = repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED", Map.of())).getId();
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = now() + interval '1 hour'"
                + " WHERE id = ?", id);

        List<OutboxEvent> ready = findReady(10);

        assertThat(ready).isEmpty();
    }

    @Test
    void findReadyToSend_whenSeveralEventsWait_returnsOldestFirstWithinLimit() {
        UUID first = repository.save(new OutboxEvent(tenantA, "FIRST", Map.of())).getId();
        UUID second = repository.save(new OutboxEvent(tenantA, "SECOND", Map.of())).getId();
        UUID third = repository.save(new OutboxEvent(tenantB, "THIRD", Map.of())).getId();
        shiftAttemptTime(first, 30);
        shiftAttemptTime(second, 20);
        shiftAttemptTime(third, 10);

        List<OutboxEvent> ready = findReady(2);

        assertThat(ready).extracting(OutboxEvent::getEventType).containsExactly("FIRST", "SECOND");
    }

    @Test
    void findReadyToSend_whenEventAlreadySent_isNotReturnedAgain() {
        UUID id = repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED", Map.of())).getId();
        jdbcTemplate.update("UPDATE outbox_events SET status = 'SENT' WHERE id = ?", id);

        assertThat(findReady(10)).isEmpty();
    }

    @Test
    void payload_whenChangedDirectlyInDatabase_isRejectedByTrigger() {
        UUID id = repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED",
                Map.of("requestId", "42"))).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET payload = '{\"requestId\": \"43\"}'::jsonb WHERE id = ?",
                id))
                .isInstanceOf(DataIntegrityViolationException.class);

        Map<String, Object> stored = repository.findByIdAndTenantId(id, tenantA)
                .orElseThrow()
                .getPayload();
        assertThat(stored).containsEntry("requestId", "42");
    }

    @Test
    void eventType_whenChangedDirectlyInDatabase_isRejectedByTrigger() {
        UUID id = repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED", Map.of())).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET event_type = 'SOMETHING_ELSE' WHERE id = ?", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deliveryFields_whenChanged_areAllowedByTrigger() {
        UUID id = repository.save(new OutboxEvent(tenantA, "REQUEST_CREATED", Map.of())).getId();

        assertThatCode(() -> jdbcTemplate.update("UPDATE outbox_events SET status = 'IN_PROGRESS',"
                + " attempts = attempts + 1, next_attempt_at = now() + interval '5 minutes',"
                + " updated_at = now() WHERE id = ?", id))
                .doesNotThrowAnyException();
    }

    @Test
    void readySelection_whenTableHasHundredsOfThousandsOfRows_usesIndex() {
        String insertManyEvents =
                """
                INSERT INTO outbox_events (id, tenant_id, event_type, payload, status, attempts,
                                           next_attempt_at, created_at, updated_at)
                SELECT gen_random_uuid(), ?, 'REQUEST_CREATED', '{}'::jsonb,
                       CASE WHEN i % 1000 = 0 THEN 'NEW' ELSE 'SENT' END,
                       0, now() - (i || ' seconds')::interval, now(), now()
                FROM generate_series(1, 200000) AS s(i)
                """;
        jdbcTemplate.update(insertManyEvents, tenantA);
        jdbcTemplate.execute("ANALYZE outbox_events");

        List<String> plan = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id FROM outbox_events WHERE status = 'NEW'"
                        + " AND next_attempt_at <= now() ORDER BY next_attempt_at LIMIT 100",
                String.class);

        assertThat(String.join("\n", plan)).contains("idx_outbox_events_status_next_attempt");
    }

    private List<OutboxEvent> findReady(int limit) {
        return repository.findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAsc(
                OutboxStatus.NEW, Instant.now(), PageRequest.of(0, limit));
    }

    private void shiftAttemptTime(UUID id, int secondsAgo) {
        Instant moment = Instant.now().minus(secondsAgo, ChronoUnit.SECONDS);
        jdbcTemplate.update("UPDATE outbox_events SET next_attempt_at = ? WHERE id = ?",
                Timestamp.from(moment), id);
    }

    private UUID createTenant(String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, ?, true, now(), now())", id, name);
        return id;
    }
}
