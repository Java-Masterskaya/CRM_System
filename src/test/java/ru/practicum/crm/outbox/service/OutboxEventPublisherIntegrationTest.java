package ru.practicum.crm.outbox.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.outbox.api.OutboxEventSender;
import ru.practicum.crm.outbox.api.OutboxMessage;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Запись события в транзакции бизнес-операции на реальной базе (T-065, DoD #87).
 *
 * <p>«Бизнес-операция» — транзакция, в которой вместе с событием меняются данные: здесь это
 * вставка арендатора. Расписание обработчика в тестовом профиле выключено, поэтому доставка
 * начинается только по явному вызову {@link OutboxProcessor#processBatch()}.
 */
@Import(OutboxEventPublisherIntegrationTest.Senders.class)
class OutboxEventPublisherIntegrationTest extends BaseIntegrationTest {

    private static final String PUBLISHED = "TEST_PUBLISHED";

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private OutboxProcessor processor;

    @Autowired
    private RecordingSender sender;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TenantContext tenantContext;

    @PersistenceContext
    private EntityManager entityManager;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        sender.reset();
        tenantId = UUID.randomUUID();
        insertTenant(tenantId);
    }

    /**
     * DoD: при успешной операции событие есть в очереди в состоянии «новое» сразу после
     * фиксации, и одно изменение порождает ровно одно событие.
     */
    @Test
    void publish_whenOperationCommits_storesExactlyOneNewEventReadyForDelivery() {
        UUID requestId = UUID.randomUUID();

        UUID id = transactionTemplate.execute(status -> publisher.publish(tenantId, PUBLISHED,
                "REQUEST", requestId, Map.of("newStatus", "DONE")));

        assertThat(jdbcTemplate.queryForList("SELECT id FROM outbox_events"
                + " WHERE aggregate_type = 'REQUEST' AND aggregate_id = ?", UUID.class,
                requestId)).containsExactly(id);
        assertThat(row(id))
                .containsEntry("tenant_id", tenantId)
                .containsEntry("event_type", PUBLISHED)
                .containsEntry("new_status", "DONE")
                .containsEntry("status", "NEW")
                .containsEntry("attempts", 0)
                .containsEntry("available_now", true);
    }

    /**
     * DoD: при откате бизнес-операции события в очереди нет — как нет и самого изменения.
     * Событие принудительно отправляется в базу до отката: строка была в транзакции, и откат
     * убрал именно её, а не помешал ей появиться.
     */
    @Test
    void publish_whenOperationRollsBack_leavesNeitherChangeNorEvent() {
        UUID createdInOperation = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            insertTenant(createdInOperation);
            publisher.publish(createdInOperation, PUBLISHED, Map.of("n", 1));
            entityManager.flush();
            assertThat(count("SELECT count(*) FROM outbox_events"))
                    .as("событие записано в транзакции операции").isEqualTo(1);
            throw new IllegalStateException("Операция не удалась");
        })).isInstanceOf(IllegalStateException.class).hasMessage("Операция не удалась");

        assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", createdInOperation))
                .isZero();
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    /**
     * DoD: внутри транзакции операции ничего не отправляется. Отправитель этого типа событий
     * не получает его ни до фиксации, ни после — доставляет только фоновый обработчик, и тогда
     * тот же отправитель его получает.
     */
    @Test
    void publish_sendsNothingItself_deliveryIsLeftToProcessor() {
        UUID id = transactionTemplate.execute(status -> {
            UUID published = publisher.publish(tenantId, PUBLISHED, Map.of("n", 1));
            assertThat(sender.received()).as("внутри транзакции").isEmpty();
            return published;
        });

        assertThat(sender.received()).as("после фиксации").isEmpty();
        assertThat(row(id)).containsEntry("attempts", 0);

        processor.processBatch();

        assertThat(sender.received()).as("доставка фоновым обработчиком")
                .extracting(OutboxMessage::id).containsExactly(id);
    }

    /** Без транзакции событие записалось бы отдельно от изменения — вызов отклоняется. */
    @Test
    void publish_withoutTransaction_isRejectedAndStoresNothing() {
        Map<String, Object> payload = Map.of("n", 1);

        assertThatThrownBy(() -> publisher.publish(tenantId, PUBLISHED, payload))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    /**
     * Ошибка проверки не портит транзакцию вызывающего (#186): операция поймала её, продолжила
     * работу и зафиксировалась. Без {@code noRollbackFor} фиксация упала бы с
     * {@code UnexpectedRollbackException}, а изменение операции пропало бы.
     */
    @Test
    void publish_whenRejectedAndCallerCatchesIt_operationStillCommits() {
        UUID createdInOperation = UUID.randomUUID();

        transactionTemplate.executeWithoutResult(status -> {
            insertTenant(createdInOperation);
            assertThatThrownBy(() -> publisher.publish(createdInOperation, " ", Map.of("n", 1)))
                    .isInstanceOf(IllegalArgumentException.class);
        });

        assertThat(count("SELECT count(*) FROM tenants WHERE id = ?", createdInOperation))
                .as("изменение операции зафиксировано").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    /** Запрос арендатора не пишет событие чужого арендатора — понятной ошибкой ещё до RLS. */
    @Test
    void publish_forAnotherTenantThanCurrentRequest_isRejected() {
        UUID otherTenant = UUID.randomUUID();
        insertTenant(otherTenant);
        tenantContext.setTenantId(tenantId);
        try {
            transactionTemplate.executeWithoutResult(status ->
                    assertThatThrownBy(() -> publisher.publish(otherTenant, PUBLISHED,
                            Map.of("n", 1))).isInstanceOf(IllegalArgumentException.class));
        } finally {
            tenantContext.clear();
        }

        assertThat(count("SELECT count(*) FROM outbox_events")).isZero();
    }

    private Map<String, Object> row(UUID id) {
        return jdbcTemplate.queryForMap("SELECT tenant_id, event_type, status, attempts,"
                + " payload ->> 'newStatus' AS new_status,"
                + " next_attempt_at <= now() AS available_now"
                + " FROM outbox_events WHERE id = ?", id);
    }

    private Integer count(String sql, Object... args) {
        return jdbcTemplate.queryForObject(sql, Integer.class, args);
    }

    private void insertTenant(UUID id) {
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', ?, true, now(), now())", id, "tenant-" + id);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Senders {

        @Bean
        RecordingSender publishedEventsSender() {
            return new RecordingSender();
        }
    }

    /** Отправитель тестового типа событий: запоминает всё, что ему передали. */
    static class RecordingSender implements OutboxEventSender {

        private final List<OutboxMessage> received = new CopyOnWriteArrayList<>();

        @Override
        public String eventType() {
            return PUBLISHED;
        }

        @Override
        public void send(OutboxMessage message) {
            received.add(message);
        }

        List<OutboxMessage> received() {
            return List.copyOf(received);
        }

        void reset() {
            received.clear();
        }
    }
}
