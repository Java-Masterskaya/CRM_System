package ru.practicum.crm.security.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.security.config.RefreshTokenCleanupProperties;
import ru.practicum.crm.security.repository.RefreshTokenRepository;
import ru.practicum.testsupport.AuthTestFixture;

/**
 * Плановая очистка refresh-токенов на реальной базе (T-029, DoD #30). Расписание в тестовом
 * профиле выключено — проходы запускаются вручную. Токены вставляются запросами SQL: так
 * проще задать им срок жизни и признаки.
 */
@Import(AuthTestFixture.class)
class RefreshTokenCleanerIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610122335";

    @Autowired
    private RefreshTokenCleaner cleaner;

    @Autowired
    private RefreshTokenRepository repository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthTestFixture authTestFixture;

    private UUID tenantId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        AuthTestFixture.TestTenant tenant = authTestFixture.createTenant("refresh-cleanup");
        tenantId = tenant.id();
        userId = authTestFixture.createUser(tenant, "user@example.com", "StrongPassword1!").id();
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    /**
     * DoD: истёкшие записи исчезают из хранилища после планового прохода — и отозванные тоже.
     * Действующие остаются, в том числе использованные: по ним обмен узнаёт повторное
     * предъявление украденного токена (T-028).
     */
    @Test
    void cleanUp_removesExpiredAndRevokedAndKeepsLiveTokens() {
        UUID expired = insertToken("now() - interval '1 second'", false, false);
        UUID revoked = insertToken("now() + interval '1 day'", false, true);
        UUID live = insertToken("now() + interval '1 day'", false, false);
        UUID usedLive = insertToken("now() + interval '1 day'", true, false);

        assertThat(cleaner.cleanUp()).isEqualTo(2);

        assertThat(storedTokens()).containsExactlyInAnyOrder(live, usedLive)
                .doesNotContain(expired, revoked);
        assertThat(cleaner.cleanUp()).as("повторный проход").isZero();
    }

    /** Порциями: при порции из двух записей пять истёкших удаляются за три порции. */
    @Test
    void cleanUp_withSmallBatches_removesEverythingBatchByBatch() {
        for (int i = 0; i < 5; i++) {
            insertToken("now() - interval '1 day'", false, false);
        }
        RefreshTokenCleaner batchesOfTwo = new RefreshTokenCleaner(repository, transactionManager,
                new RefreshTokenCleanupProperties(2, Duration.ofHours(1)), Clock.systemUTC());

        assertThat(batchesOfTwo.cleanUp()).isEqualTo(5);

        assertThat(storedTokens()).isEmpty();
    }

    /**
     * Строку держит идущий обмен или отзыв: очистка её пропускает, а не ждёт, и удаляет
     * остальные; придержанную удалит следующий проход.
     */
    @Test
    void cleanUp_whenRowIsLocked_skipsItInsteadOfWaiting() throws Exception {
        UUID held = insertToken("now() - interval '1 day'", false, false);
        UUID free = insertToken("now() - interval '1 day'", false, false);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<?> holder = executor.submit(() ->
                    transactionTemplate.executeWithoutResult(status -> {
                        jdbcTemplate.queryForList(
                                "SELECT id FROM refresh_tokens WHERE id = ? FOR UPDATE", held);
                        locked.countDown();
                        await(release);
                    }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            int purged = cleaner.cleanUp();
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);

            assertThat(purged).isEqualTo(1);
            assertThat(storedTokens()).containsExactly(held).doesNotContain(free);
        }
        assertThat(cleaner.cleanUp()).isEqualTo(1);
        assertThat(storedTokens()).isEmpty();
    }

    /**
     * Двадцать тысяч действующих токенов и по десятку истёкших и отозванных: порция на удаление
     * находится по индексу, а не перебором таблицы. Условия — те же, что в запросах очистки.
     */
    @Test
    void cleanupSelections_whenTableIsLarge_useTheirIndexes() {
        jdbcTemplate.update("INSERT INTO refresh_tokens (id, family_id, user_id, tenant_id,"
                + " token_hash, expires_at, created_at)"
                + " SELECT gen_random_uuid(), gen_random_uuid(), ?, ?, md5('live-' || i),"
                + " now() + interval '7 days', now() FROM generate_series(1, 20000) AS s(i)",
                userId, tenantId);
        for (int i = 0; i < 10; i++) {
            insertToken("now() - interval '1 day'", false, false);
            insertToken("now() + interval '1 day'", false, true);
        }
        jdbcTemplate.execute("ANALYZE refresh_tokens");

        assertThat(plan("SELECT id FROM refresh_tokens WHERE expires_at <= now() LIMIT 500"))
                .as("порция истёкших").contains("idx_refresh_tokens_expires_at")
                .doesNotContain("Seq Scan");
        assertThat(plan("SELECT id FROM refresh_tokens WHERE revoked_at IS NOT NULL LIMIT 500"))
                .as("порция отозванных").contains("idx_refresh_tokens_revoked")
                .doesNotContain("Seq Scan");
    }

    private UUID insertToken(String expiresAt, boolean used, boolean revoked) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO refresh_tokens (id, family_id, user_id, tenant_id,"
                + " token_hash, expires_at, created_at, used_at, revoked_at)"
                + " VALUES (?, ?, ?, ?, ?, " + expiresAt + ", now() - interval '2 days',"
                + " CASE WHEN ?::boolean THEN now() END, CASE WHEN ?::boolean THEN now() END)",
                id, UUID.randomUUID(), userId, tenantId, "hash-" + id, used, revoked);
        return id;
    }

    private List<UUID> storedTokens() {
        return jdbcTemplate.queryForList("SELECT id FROM refresh_tokens", UUID.class);
    }

    private String plan(String query) {
        return String.join("\n", jdbcTemplate.queryForList("EXPLAIN " + query, String.class));
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Тест не дождался сигнала");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }
}
