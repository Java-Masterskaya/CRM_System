package ru.practicum.crm.outbox.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.outbox.config.OutboxProperties;
import ru.practicum.crm.outbox.domain.DeliveryFailure;
import ru.practicum.crm.outbox.domain.OutboxEvent;
import ru.practicum.crm.outbox.domain.OutboxStatus;
import ru.practicum.crm.outbox.repository.OutboxEventRepository;

/**
 * Фоновый обработчик исходящих событий (T-066, #88).
 *
 * <p>Один проход — три шага, и каждый в своей короткой транзакции или вне её:
 * <ol>
 *   <li><b>Захват.</b> Порция готовых событий блокируется с {@code SKIP LOCKED} и переводится
 *       в {@link OutboxStatus#IN_PROGRESS} со сроком аренды. Транзакция сразу фиксируется:
 *       блокировки снимаются, но другой экземпляр приложения эти события уже не возьмёт — они
 *       не новые, а срок аренды ещё не вышел.</li>
 *   <li><b>Отправка</b> — вне транзакции: медленный внешний сервис не держит соединение с
 *       базой.</li>
 *   <li><b>Результат</b> — отдельная транзакция на каждое событие: {@link OutboxStatus#SENT},
 *       возврат в очередь с нарастающей задержкой ({@link OutboxRetryPolicy}) или, когда
 *       попытки исчерпаны, {@link OutboxStatus#FAILED} с причиной.</li>
 * </ol>
 *
 * <p>Если приложение упадёт между захватом и записью результата, событие останется
 * {@code IN_PROGRESS}; когда срок аренды истечёт, захват вернёт его снова. Гарантия — «хотя бы
 * один раз»: письмо, ушедшее прямо перед падением, может уйти повторно.
 *
 * <p>Результат записывается, только если событие всё ещё за этим обработчиком: в
 * {@code IN_PROGRESS} и с тем же сроком аренды, что был выставлен при захвате. Если отправка
 * длилась дольше аренды и событие успел взять другой обработчик, его запись не затирается.
 */
@Component
public class OutboxProcessor {

    static final String NO_SENDER = "NO_SENDER";
    static final String UNEXPECTED_ERROR = "UNEXPECTED_ERROR";

    private static final Logger LOG = LoggerFactory.getLogger(OutboxProcessor.class);

    private final OutboxEventRepository repository;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;
    private final OutboxRetryPolicy retryPolicy;
    private final Map<String, OutboxEventSender> senders;

    private volatile boolean stopping;

    public OutboxProcessor(OutboxEventRepository repository,
            PlatformTransactionManager transactionManager, OutboxProperties properties,
            ObjectProvider<OutboxEventSender> senders) {
        this.repository = repository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.properties = properties;
        this.retryPolicy = new OutboxRetryPolicy(properties.retryDelay(),
                properties.maxRetryDelay(), properties.maxAttempts());
        this.senders = senders.orderedStream()
                .collect(Collectors.toUnmodifiableMap(OutboxEventSender::eventType,
                        Function.identity(), (first, second) -> {
                            throw new IllegalStateException("Два отправителя для одного типа "
                                    + "событий: " + first.eventType());
                        }));
    }

    /**
     * Приложение останавливается: текущее событие дописывается, остальные из порции
     * возвращаются в очередь. Событие о закрытии контекста публикуется раньше, чем
     * останавливается планировщик, поэтому флаг успевает сработать.
     */
    @EventListener(ContextClosedEvent.class)
    public void onApplicationShutdown() {
        stopping = true;
    }

    /**
     * Один проход: захватить порцию, отправить, записать результаты.
     *
     * @return сколько событий было отправлено или попытались отправить
     */
    public int processBatch() {
        return processBatch(() -> stopping);
    }

    int processBatch(BooleanSupplier stopRequested) {
        if (stopRequested.getAsBoolean()) {
            return 0;
        }
        Instant leaseUntil = Instant.now().plus(properties.lease()).truncatedTo(ChronoUnit.MICROS);
        List<OutboxEvent> claimed = claim(leaseUntil);
        int processed = 0;
        for (OutboxEvent event : claimed) {
            if (stopRequested.getAsBoolean()) {
                release(claimed.subList(processed, claimed.size()), leaseUntil);
                break;
            }
            Optional<DeliveryFailure> failure = send(event);
            recordResult(event, leaseUntil, failure);
            processed++;
        }
        return processed;
    }

    private List<OutboxEvent> claim(Instant leaseUntil) {
        List<OutboxEvent> batch = transactionTemplate.execute(status -> {
            List<OutboxEvent> ready = repository.lockReadyBatch(Instant.now(),
                    properties.batchSize());
            ready.forEach(event -> event.claim(leaseUntil));
            return ready;
        });
        return batch == null ? List.of() : batch;
    }

    /**
     * Отправляет событие и возвращает причину неудачи, если она была.
     *
     * <p>Текст исключения стороннего кода не сохраняется и не пишется в лог: в нём бывает адрес
     * получателя или ответ почтового сервера. Для {@link OutboxDeliveryException} берутся код и
     * сообщение, которые отправитель сформировал сам; для любого другого исключения — только
     * имя класса.
     */
    private Optional<DeliveryFailure> send(OutboxEvent event) {
        OutboxEventSender sender = senders.get(event.getEventType());
        if (sender == null) {
            return Optional.of(new DeliveryFailure(NO_SENDER,
                    "Нет отправителя для событий типа " + event.getEventType()));
        }
        try {
            sender.send(event);
            return Optional.empty();
        } catch (OutboxDeliveryException ex) {
            return Optional.of(new DeliveryFailure(ex.getCode(), ex.getMessage()));
        } catch (RuntimeException ex) {
            return Optional.of(new DeliveryFailure(UNEXPECTED_ERROR,
                    ex.getClass().getSimpleName()));
        }
    }

    private void recordResult(OutboxEvent event, Instant leaseUntil,
            Optional<DeliveryFailure> failure) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<OutboxEvent> owned = findOwned(event.getId(), leaseUntil);
            if (owned.isEmpty()) {
                LOG.warn("Событие {} за время отправки перешло к другому обработчику: "
                        + "результат не записан", event.getId());
                return;
            }
            if (failure.isEmpty()) {
                owned.get().markSent();
            } else {
                registerFailure(owned.get(), failure.get());
            }
        });
    }

    /**
     * В лог попадают только идентификатор, тип, номер попытки и код причины — без полезной
     * нагрузки и сообщения: так в нём не окажутся адреса, текст письма и токены.
     */
    private void registerFailure(OutboxEvent event, DeliveryFailure failure) {
        int attempts = event.getAttempts();
        if (retryPolicy.isExhausted(attempts)) {
            event.markFailed(failure);
            LOG.warn("Событие {} типа {} окончательно не доставлено после {} попыток: {}",
                    event.getId(), event.getEventType(), attempts, failure.code());
            return;
        }
        Duration delay = retryPolicy.delayAfter(attempts);
        event.retryAt(Instant.now().plus(delay), failure);
        LOG.warn("Событие {} типа {} не доставлено ({}), попытка {} из {}; следующая через {}",
                event.getId(), event.getEventType(), failure.code(), attempts,
                properties.maxAttempts(), delay);
    }

    private void release(List<OutboxEvent> unstarted, Instant leaseUntil) {
        transactionTemplate.executeWithoutResult(status -> {
            Instant now = Instant.now();
            for (OutboxEvent event : unstarted) {
                findOwned(event.getId(), leaseUntil)
                        .ifPresent(owned -> owned.releaseUnstarted(now));
            }
        });
        LOG.info("Остановка приложения: {} событий возвращено в очередь", unstarted.size());
    }

    private Optional<OutboxEvent> findOwned(UUID eventId, Instant leaseUntil) {
        return repository.findByIdAndStatus(eventId, OutboxStatus.IN_PROGRESS)
                .filter(event -> leaseUntil.equals(event.getNextAttemptAt()));
    }
}
