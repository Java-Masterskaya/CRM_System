package ru.practicum.crm.outbox.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.domain.OutboxEvent;

/**
 * Ссылка события на объект (#146) на реальной базе: выборка событий объекта, неизменяемость
 * ссылки, ограничение «целиком или никак» и миграция поверх уже записанных событий.
 */
class OutboxEventAggregateIntegrationTest extends BaseIntegrationTest {

    /** Последняя миграция перед этой: до неё накатывается схема в проверке на заполненной базе. */
    private static final String PREVIOUS_MIGRATION = "202609241534";
    private static final String REQUEST = "REQUEST";

    @Autowired
    private OutboxEventRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant();
        tenantB = insertTenant();
    }

    @Test
    void findEventsOfObject_returnsOnlyThatObjectsEventsInCreationOrder() {
        UUID requestId = UUID.randomUUID();
        final UUID later = saveAboutRequest(tenantA, requestId, "2026-09-26T10:05:00Z");
        final UUID earlier = saveAboutRequest(tenantA, requestId, "2026-09-26T10:00:00Z");
        saveAboutRequest(tenantA, UUID.randomUUID(), "2026-09-26T10:01:00Z");
        saveAboutRequest(tenantB, requestId, "2026-09-26T10:02:00Z");
        repository.save(new OutboxEvent(tenantA, "TENANT_SETTINGS_CHANGED", Map.of()));

        List<OutboxEvent> events = repository
                .findByTenantIdAndAggregateTypeAndAggregateIdOrderByCreatedAtAscIdAsc(
                        tenantA, REQUEST, requestId);

        assertThat(events).extracting(OutboxEvent::getId).containsExactly(earlier, later);
    }

    @Test
    void objectReference_whenChangedDirectlyInDatabase_isRejectedByTrigger() {
        UUID withReference = saveAboutRequest(tenantA, UUID.randomUUID(), "2026-09-26T10:00:00Z");
        UUID withoutReference = repository.save(
                new OutboxEvent(tenantA, "TENANT_SETTINGS_CHANGED", Map.of())).getId();

        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET aggregate_id = ? WHERE id = ?",
                UUID.randomUUID(), withReference))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update(
                "UPDATE outbox_events SET aggregate_type = 'COMMENT' WHERE id = ?", withReference))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("UPDATE outbox_events"
                + " SET aggregate_type = 'REQUEST', aggregate_id = ? WHERE id = ?",
                UUID.randomUUID(), withoutReference))
                .as("дописать ссылку задним числом тоже нельзя")
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void incompleteObjectReference_isRejectedByDatabase() {
        assertThatThrownBy(() -> jdbcTemplate.update("INSERT INTO outbox_events (id, tenant_id,"
                + " event_type, payload, status, next_attempt_at, created_at, updated_at,"
                + " aggregate_type) VALUES (?, ?, 'REQUEST_CREATED', '{}', 'NEW', now(), now(),"
                + " now(), 'REQUEST')", UUID.randomUUID(), tenantA))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("outbox_events_aggregate_complete_check");
    }

    @Test
    void findEventsOfObject_onLargeQueue_usesAggregateIndex() {
        jdbcTemplate.update("INSERT INTO outbox_events (id, tenant_id, event_type, payload,"
                + " status, next_attempt_at, created_at, updated_at, aggregate_type, aggregate_id)"
                + " SELECT gen_random_uuid(), ?, 'REQUEST_CREATED', '{}', 'SENT', now(), now(),"
                + " now(), 'REQUEST', gen_random_uuid() FROM generate_series(1, 5000)", tenantA);
        jdbcTemplate.execute("ANALYZE outbox_events");

        List<String> plan = jdbcTemplate.queryForList("EXPLAIN SELECT * FROM outbox_events"
                + " WHERE tenant_id = '" + tenantA + "' AND aggregate_type = 'REQUEST'"
                + " AND aggregate_id = '" + UUID.randomUUID() + "' ORDER BY created_at, id",
                String.class);

        assertThat(String.join("\n", plan)).contains("idx_outbox_events_aggregate");
    }

    @Test
    void migration_onDatabaseThatAlreadyHasEvents_keepsThemWithoutReference() {
        String schema = "migration_check_" + UUID.randomUUID().toString().replace("-", "");
        UUID tenantId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        try {
            migrate(schema, PREVIOUS_MIGRATION);
            jdbcTemplate.update("INSERT INTO " + schema + ".tenants (id, name, active,"
                    + " created_at, updated_at) VALUES (?, 'Арендатор', true, now(), now())",
                    tenantId);
            jdbcTemplate.update("INSERT INTO " + schema + ".outbox_events (id, tenant_id,"
                    + " event_type, payload, status, next_attempt_at, created_at, updated_at)"
                    + " VALUES (?, ?, 'REQUEST_CREATED', '{}', 'NEW', now(), now(), now())",
                    eventId, tenantId);

            migrate(schema, null);

            assertThat(jdbcTemplate.queryForMap("SELECT event_type, aggregate_type, aggregate_id"
                    + " FROM " + schema + ".outbox_events WHERE id = ?", eventId))
                    .containsEntry("event_type", "REQUEST_CREATED")
                    .containsEntry("aggregate_type", null)
                    .containsEntry("aggregate_id", null);
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private UUID saveAboutRequest(UUID tenantId, UUID requestId, String createdAt) {
        UUID id = repository.save(OutboxEvent.aboutObject(tenantId, "REQUEST_CREATED", REQUEST,
                requestId, Map.of())).getId();
        jdbcTemplate.update("UPDATE outbox_events SET created_at = ?::timestamptz WHERE id = ?",
                createdAt, id);
        return id;
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", id);
        return id;
    }

    /**
     * Накатывает миграции проекта в отдельную схему той же базы: до {@code target} или, если он
     * не задан, до последней. Отдельная схема не мешает основной, с которой работает приложение.
     */
    private static void migrate(String schema, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }
}
