package ru.practicum.crm.security.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.security.config.RefreshTokenCleanupProperties;
import ru.practicum.crm.security.repository.RefreshTokenRepository;

/**
 * Плановая очистка refresh-токенов (T-029): удаляет истёкшие и отозванные записи, чтобы
 * хранилище не росло с каждым входом и обменом.
 *
 * <p>Записи удаляются порциями, каждая порция — один запрос в своей короткой транзакции: проход
 * не держит долгих блокировок, а остановка приложения посреди прохода оставляет удалённой
 * только целые порции — остаток удалит следующий проход. Строки, которые держит идущий обмен
 * или отзыв, пропускаются, а не ждут.
 *
 * <p>Момент отсчёта один на весь проход: токены, истёкшие по ходу, достанутся следующему, и
 * проход всегда заканчивается.
 */
@Component
public class RefreshTokenCleaner {

    private static final Logger LOG = LoggerFactory.getLogger(RefreshTokenCleaner.class);

    private final RefreshTokenRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final RefreshTokenCleanupProperties properties;
    private final Clock clock;

    private volatile boolean stopping;

    public RefreshTokenCleaner(RefreshTokenRepository repository,
            PlatformTransactionManager transactionManager,
            RefreshTokenCleanupProperties properties, Clock clock) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Приложение останавливается: текущая порция дописывается, следующая не начинается. Событие о
     * закрытии контекста публикуется раньше, чем останавливается планировщик.
     */
    @EventListener(ContextClosedEvent.class)
    public void onApplicationShutdown() {
        stopping = true;
    }

    /**
     * Один проход.
     *
     * @return сколько записей удалено
     */
    public int cleanUp() {
        return cleanUp(() -> stopping);
    }

    int cleanUp(BooleanSupplier stopRequested) {
        OffsetDateTime now = clock.instant().atOffset(ZoneOffset.UTC);
        int expired = purgeAll(limit -> repository.purgeExpired(now, limit), stopRequested);
        int revoked = purgeAll(repository::purgeRevoked, stopRequested);
        if (expired + revoked > 0) {
            LOG.info("Удалены refresh-токены: истёкшие {}, отозванные {}",
                    value("expiredTokens", expired), value("revokedTokens", revoked));
        }
        return expired + revoked;
    }

    /** Порция за порцией, пока порция полная и остановку не просили. */
    private int purgeAll(IntUnaryOperator batch, BooleanSupplier stopRequested) {
        int total = 0;
        while (!stopRequested.getAsBoolean()) {
            Integer purged = transactionTemplate.execute(
                    status -> batch.applyAsInt(properties.batchSize()));
            int count = purged == null ? 0 : purged;
            total += count;
            if (count < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
