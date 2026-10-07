package ru.practicum.crm.outbox.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.Getter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Исходящее событие: факт изменения, который нужно доставить наружу — письмом или вебхуком.
 *
 * <p>Запись создаётся в той же транзакции, что и само изменение (SPEC §3.5), а доставка идёт
 * отдельным процессом. Поэтому сбой почты не откатывает бизнес-операцию, а откат операции не
 * оставляет события, которое некому объяснить.
 *
 * <p>Событие может ссылаться на объект, к которому относится, — вид объекта и его идентификатор
 * ({@code aggregateType}, {@code aggregateId}). По этой ссылке выбираются все события одного
 * объекта. Ссылка задаётся целиком или не задаётся вовсе.
 *
 * <p>Событие — свершившийся факт: арендатор, тип, ссылка на объект и полезная нагрузка после
 * записи не меняются.
 * В коде это выражено отсутствием сеттеров и {@code updatable = false}, в базе — триггером
 * {@code outbox_events_immutable_payload}. Меняются только поля доставки, и только методами
 * {@link #claim}, {@link #markSent}, {@link #retryAt}, {@link #markFailed} и
 * {@link #releaseUnstarted}, которые
 * вызывает фоновый обработчик доставки.
 */
@Entity
@Table(name = "outbox_events")
@Getter
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id", nullable = false, updatable = false)
    private UUID id;

    @Column(name = "tenant_id", nullable = false, updatable = false)
    private UUID tenantId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 100)
    private String eventType;

    @Column(name = "aggregate_type", updatable = false, length = 100)
    private String aggregateType;

    @Column(name = "aggregate_id", updatable = false)
    private UUID aggregateId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, updatable = false)
    private Map<String, Object> payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status = OutboxStatus.NEW;

    @Column(name = "attempts", nullable = false)
    private int attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private Instant nextAttemptAt;

    @Column(name = "last_error_code", length = DeliveryFailure.CODE_MAX_LENGTH)
    private String lastErrorCode;

    @Column(name = "last_error_message", length = DeliveryFailure.MESSAGE_MAX_LENGTH)
    private String lastErrorMessage;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected OutboxEvent() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже
        // или метод aboutObject.
    }

    /** Событие, не относящееся к конкретному объекту. */
    public OutboxEvent(UUID tenantId, String eventType, Map<String, Object> payload) {
        this(tenantId, eventType, null, null, payload);
    }

    private OutboxEvent(UUID tenantId, String eventType, String aggregateType, UUID aggregateId,
            Map<String, Object> payload) {
        this.tenantId = tenantId;
        this.eventType = eventType;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.payload = payload == null ? null : new LinkedHashMap<>(payload);
    }

    /**
     * Событие об объекте: {@code aggregateType} — вид объекта (например, {@code REQUEST}),
     * {@code aggregateId} — его идентификатор.
     *
     * <p>Фабричный метод, а не конструктор: ссылка проверяется до того, как объект начнёт
     * создаваться. Исключение из конструктора оставило бы недособранный объект, а сделать класс
     * {@code final}, чтобы это стало безопасно, нельзя — Hibernate создаёт от сущностей
     * классы-заместители.
     *
     * @throws IllegalArgumentException если ссылка на объект задана не целиком
     */
    public static OutboxEvent aboutObject(UUID tenantId, String eventType, String aggregateType,
            UUID aggregateId, Map<String, Object> payload) {
        if (aggregateType == null || aggregateType.isBlank() || aggregateId == null) {
            throw new IllegalArgumentException(
                    "Ссылка на объект задаётся целиком: и вид объекта, и его идентификатор");
        }
        return new OutboxEvent(tenantId, eventType, aggregateType, aggregateId, payload);
    }

    public Map<String, Object> getPayload() {
        return payload == null ? null : new LinkedHashMap<>(payload);
    }

    /**
     * Обработчик забирает событие: начинается очередная попытка доставки.
     *
     * <p>До {@code leaseUntil} событие принадлежит этому обработчику — выборка готовых его не
     * вернёт. Если обработчик упадёт, не записав результат, после этого момента событие снова
     * станет доступно, и оно не потеряется.
     *
     * @throws IllegalStateException если событие уже доставлено или окончательно не удалось
     */
    public void claim(Instant leaseUntil) {
        if (status != OutboxStatus.NEW && status != OutboxStatus.IN_PROGRESS) {
            throw new IllegalStateException("Событие " + id + " в состоянии " + status
                    + " брать в обработку нельзя");
        }
        status = OutboxStatus.IN_PROGRESS;
        attempts++;
        nextAttemptAt = leaseUntil;
    }

    /** Доставка удалась: событие больше не выбирается. */
    public void markSent() {
        requireInProgress();
        status = OutboxStatus.SENT;
    }

    /**
     * Попытка не удалась, но попытки ещё есть: событие возвращается в очередь и станет доступно
     * в {@code nextAttempt}. Счётчик попыток не меняется — эта попытка уже учтена в
     * {@link #claim}. Причина запоминается для разбора.
     */
    public void retryAt(Instant nextAttempt, DeliveryFailure failure) {
        requireInProgress();
        status = OutboxStatus.NEW;
        nextAttemptAt = nextAttempt;
        remember(failure);
    }

    /**
     * Попытки исчерпаны: окончательный неуспех. Событие остаётся в таблице для разбора и больше
     * не выбирается — захват берёт только новые и взятые в работу.
     */
    public void markFailed(DeliveryFailure failure) {
        requireInProgress();
        status = OutboxStatus.FAILED;
        remember(failure);
    }

    private void remember(DeliveryFailure failure) {
        lastErrorCode = failure.code();
        lastErrorMessage = failure.message();
    }

    /**
     * Событие взято, но отправка даже не начиналась — например, приложение останавливается.
     * Оно возвращается в очередь сразу, а засчитанная при взятии попытка отменяется.
     */
    public void releaseUnstarted(Instant now) {
        requireInProgress();
        status = OutboxStatus.NEW;
        attempts--;
        nextAttemptAt = now;
    }

    private void requireInProgress() {
        if (status != OutboxStatus.IN_PROGRESS) {
            throw new IllegalStateException("Событие " + id + " не в обработке, а в состоянии "
                    + status);
        }
    }

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        createdAt = now;
        updatedAt = now;
        if (nextAttemptAt == null) {
            nextAttemptAt = now;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OutboxEvent event)) {
            return false;
        }
        return id != null && id.equals(event.id);
    }

    @Override
    public int hashCode() {
        return OutboxEvent.class.hashCode();
    }
}
