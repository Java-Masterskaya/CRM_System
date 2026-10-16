package ru.practicum.crm.request.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Clock;
import java.time.Instant;
import java.util.function.BooleanSupplier;
import java.util.function.IntUnaryOperator;
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
 *
 * <p>После пометки проход рассылает письма о нарушенных сроках ({@link OverdueEscalation}, T-061)
 * — тоже порциями в своих транзакциях. Эскалация идёт по своим признакам, а не по только что
 * помеченным заявкам: если приложение остановится между пометкой и письмами, письма разошлёт
 * следующий проход.
 */
@Component
public class OverdueRequestDetector {

    private static final Logger LOG = LoggerFactory.getLogger(OverdueRequestDetector.class);

    private final RequestRepository repository;
    private final OverdueEscalation escalation;
    private final TransactionTemplate transactionTemplate;
    private final OverdueDetectionProperties properties;
    private final Clock clock;

    private volatile boolean stopping;

    @Autowired
    public OverdueRequestDetector(RequestRepository repository, OverdueEscalation escalation,
            PlatformTransactionManager transactionManager,
            OverdueDetectionProperties properties) {
        this(repository, escalation, transactionManager, properties, Clock.systemUTC());
    }

    /** Для тестов: часы, по которым берётся момент проверки. */
    OverdueRequestDetector(RequestRepository repository, OverdueEscalation escalation,
            PlatformTransactionManager transactionManager, OverdueDetectionProperties properties,
            Clock clock) {
        this.repository = repository;
        this.escalation = escalation;
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
     * Один проход: пометка просрочки, затем эскалация.
     *
     * @return сколько признаков просрочки проставлено
     */
    public int detect() {
        return detect(() -> stopping);
    }

    int detect(BooleanSupplier stopRequested) {
        Instant now = clock.instant();
        int firstResponse = inBatches(
                limit -> repository.markFirstResponseOverdue(now, limit), stopRequested);
        int resolution = inBatches(
                limit -> repository.markResolutionOverdue(now, limit), stopRequested);
        if (firstResponse + resolution > 0) {
            LOG.info("Просроченные заявки помечены: по первому ответу {}, по решению {}",
                    value("firstResponseOverdue", firstResponse),
                    value("resolutionOverdue", resolution));
        }
        int firstResponseEscalated = inBatches(limit -> escalation.escalateBatch(
                OverdueEscalation.Deadline.FIRST_RESPONSE, limit), stopRequested);
        int resolutionEscalated = inBatches(limit -> escalation.escalateBatch(
                OverdueEscalation.Deadline.RESOLUTION, limit), stopRequested);
        if (firstResponseEscalated + resolutionEscalated > 0) {
            LOG.info("Просроченные заявки эскалированы: по первому ответу {}, по решению {}",
                    value("firstResponseEscalated", firstResponseEscalated),
                    value("resolutionEscalated", resolutionEscalated));
        }
        return firstResponse + resolution;
    }

    /**
     * Порция за порцией, каждая в своей транзакции, пока порция полная и остановку не просили.
     *
     * @param batch обработка одной порции: принимает её размер, возвращает, сколько заявок
     *     обработано
     * @return сколько заявок обработано за все порции
     */
    private int inBatches(IntUnaryOperator batch, BooleanSupplier stopRequested) {
        int total = 0;
        while (!stopRequested.getAsBoolean()) {
            Integer done = transactionTemplate.execute(
                    status -> batch.applyAsInt(properties.batchSize()));
            int count = done == null ? 0 : done;
            total += count;
            if (count < properties.batchSize()) {
                break;
            }
        }
        return total;
    }
}
