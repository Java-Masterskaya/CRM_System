package ru.practicum.crm.request.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.request.config.OverdueDetectionProperties;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Фоновое выявление просроченных заявок (T-060, ТЗ §6.4, SPEC §3.7).
 *
 * <p>Проход помечает заявки, у которых истёк срок первого ответа или срок решения: признаки
 * {@code first_response_overdue} и {@code resolution_overdue} раздельные. Просрочка — признак, а
 * не статус: статус заявки не меняется, матрица переходов не затрагивается.
 *
 * <p>Заявки помечаются порциями, каждая порция — один запрос в своей короткой транзакции. Поэтому
 * остановка приложения посреди прохода не оставляет заявку помеченной наполовину: порция либо
 * записана целиком, либо не записана, а остаток пометит следующий проход. Два экземпляра
 * приложения не возьмут одну заявку — порция берётся с {@code SKIP LOCKED}.
 *
 * <p>Проход идемпотентен: уже помеченные заявки в выборку не попадают, повторный проход их не
 * меняет. Момент проверки один на весь проход — заявки, просрочившиеся по ходу, достанутся
 * следующему, и проход всегда заканчивается.
 *
 * <p>Каждый признак — отдельное изменение заявки, и версия растёт на единицу за каждый: если к
 * проходу истекли оба срока, версия вырастет на два. Обычно же сроки истекают в разные проходы —
 * срок первого ответа раньше.
 */
@Component
public class OverdueRequestDetector {

    private static final Logger LOG = LoggerFactory.getLogger(OverdueRequestDetector.class);

    private final RequestRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final OverdueDetectionProperties properties;
    private final Clock clock;

    private volatile boolean stopping;

    @Autowired
    public OverdueRequestDetector(RequestRepository repository,
            PlatformTransactionManager transactionManager,
            OverdueDetectionProperties properties) {
        this(repository, transactionManager, properties, Clock.systemUTC());
    }

    /** Для тестов: часы, по которым берётся момент проверки. */
    OverdueRequestDetector(RequestRepository repository,
            PlatformTransactionManager transactionManager, OverdueDetectionProperties properties,
            Clock clock) {
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
     * @return сколько признаков просрочки проставлено
     */
    public int detect() {
        return detect(() -> stopping);
    }

    int detect(BooleanSupplier stopRequested) {
        Instant now = clock.instant();
        int firstResponse = markAll(now, repository::markFirstResponseOverdue, stopRequested);
        int resolution = markAll(now, repository::markResolutionOverdue, stopRequested);
        if (firstResponse + resolution > 0) {
            LOG.info("Просроченные заявки помечены: по первому ответу {}, по решению {}",
                    value("firstResponseOverdue", firstResponse),
                    value("resolutionOverdue", resolution));
        }
        return firstResponse + resolution;
    }

    /** Порция за порцией, пока порция полная и остановку не просили. */
    private int markAll(Instant now, BatchMarker marker, BooleanSupplier stopRequested) {
        int total = 0;
        while (!stopRequested.getAsBoolean()) {
            Integer marked = transactionTemplate.execute(
                    status -> marker.mark(now, properties.batchSize()));
            int count = marked == null ? 0 : marked;
            total += count;
            if (count < properties.batchSize()) {
                break;
            }
        }
        return total;
    }

    /** Пометка одной порции: {@link RequestRepository#markFirstResponseOverdue} и т. п. */
    @FunctionalInterface
    private interface BatchMarker {
        int mark(Instant now, int limit);
    }
}
