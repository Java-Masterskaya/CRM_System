package ru.practicum.crm.request.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.notification.api.EmailNotification;
import ru.practicum.crm.notification.api.NotificationType;
import ru.practicum.crm.outbox.api.OutboxPublisher;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;
import ru.practicum.crm.user.api.UserService;
import ru.practicum.crm.user.api.dto.UserContactDto;

/**
 * Эскалация просроченных заявок (T-061, ТЗ §6.4 и §6.5, SPEC §3.7): письма о нарушенном сроке
 * исполнителю и администраторам арендатора — по одному разу на каждый срок.
 *
 * <p>Порция — одна транзакция: заявки блокируются ({@code SKIP LOCKED}), по каждой в outbox
 * записываются письма, и заявки отмечаются эскалированными. Не записались письма — не встанет и
 * отметка, и следующий проход попробует снова. Эскалированную заявку следующий проход уже не
 * выберет, поэтому повтор писем не шлёт.
 *
 * <p>Получатели — исполнитель, если он есть и может работать в системе, и администраторы:
 * активные пользователи с правом {@link #ADMIN_PERMISSION}. Кого считать администратором,
 * решают права ролей, а не название роли (T-030). Один человек получает одно письмо, даже если
 * он и исполнитель, и администратор. Если получателей нет, заявка всё равно отмечается — иначе
 * каждый проход выбирал бы её снова, — а в журнал пишется предупреждение.
 */
@Component
public class OverdueEscalation {

    /** Администраторы для эскалаций — те, кто управляет сроками. */
    static final String ADMIN_PERMISSION = "SLA_POLICY_MANAGE";

    /** Вид объекта в ссылке события: по ней выбираются все письма одной заявки. */
    static final String AGGREGATE_TYPE = "REQUEST";

    /** Пояс, если настроек арендатора нет: тот же, что у настроек по умолчанию (T-017). */
    static final ZoneId FALLBACK_ZONE = ZoneId.of("UTC");

    private static final Logger LOG = LoggerFactory.getLogger(OverdueEscalation.class);
    private static final DateTimeFormatter DUE_AT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private final RequestRepository repository;
    private final UserService users;
    private final TenantTimezoneProvider timezones;
    private final OutboxPublisher outbox;

    public OverdueEscalation(RequestRepository repository, UserService users,
            TenantTimezoneProvider timezones, OutboxPublisher outbox) {
        this.repository = repository;
        this.users = users;
        this.timezones = timezones;
        this.outbox = outbox;
    }

    /** Нарушенный срок; по каждому — своя эскалация и своё письмо. */
    public enum Deadline {
        FIRST_RESPONSE("первого ответа"),
        RESOLUTION("решения");

        /** Как срок называется в письме: «истёк срок …». */
        private final String title;

        Deadline(String title) {
            this.title = title;
        }

        public String title() {
            return title;
        }
    }

    /**
     * Эскалирует одну порцию заявок по сроку. Вызывается в транзакции порции.
     *
     * @return сколько заявок эскалировано; меньше {@code limit} — больше эскалировать нечего
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int escalateBatch(Deadline deadline, int limit) {
        List<Request> batch = switch (deadline) {
            case FIRST_RESPONSE -> repository.lockFirstResponseToEscalate(limit);
            case RESOLUTION -> repository.lockResolutionToEscalate(limit);
        };
        if (batch.isEmpty()) {
            return 0;
        }
        Map<UUID, List<UserContactDto>> adminsByTenant = new HashMap<>();
        Map<UUID, ZoneId> zoneByTenant = new HashMap<>();
        for (Request request : batch) {
            UUID tenantId = request.getTenantId();
            notifyAbout(request, deadline,
                    adminsByTenant.computeIfAbsent(tenantId,
                            tenant -> users.findActiveWithPermission(tenant, ADMIN_PERMISSION)),
                    zoneByTenant.computeIfAbsent(tenantId,
                            tenant -> timezones.findTimezoneOf(tenant).orElse(FALLBACK_ZONE)));
        }
        List<UUID> ids = batch.stream().map(Request::getId).toList();
        return switch (deadline) {
            case FIRST_RESPONSE -> repository.markFirstResponseEscalated(ids);
            case RESOLUTION -> repository.markResolutionEscalated(ids);
        };
    }

    private void notifyAbout(Request request, Deadline deadline, List<UserContactDto> admins,
            ZoneId zone) {
        Map<UUID, UserContactDto> recipients = new LinkedHashMap<>();
        if (request.getAssigneeId() != null) {
            users.findActiveContact(request.getTenantId(), request.getAssigneeId())
                    .ifPresent(assignee -> recipients.put(assignee.id(), assignee));
        }
        admins.forEach(admin -> recipients.putIfAbsent(admin.id(), admin));
        if (recipients.isEmpty()) {
            LOG.warn("Эскалация без получателей: у заявки {} арендатора {} нет активного"
                            + " исполнителя, а у арендатора — пользователей с правом {}",
                    value("requestId", request.getId()), value("tenantId", request.getTenantId()),
                    ADMIN_PERMISSION);
            return;
        }
        String dueAt = formatDueAt(dueAtOf(request, deadline), zone);
        for (UserContactDto recipient : recipients.values()) {
            Map<String, String> variables = Map.of(
                    "recipientName", nameOf(recipient),
                    "requestId", request.getId().toString(),
                    "requestSubject", request.getSubject(),
                    "deadlineName", deadline.title(),
                    "dueAt", dueAt);
            outbox.publish(request.getTenantId(), EmailNotification.EVENT_TYPE, AGGREGATE_TYPE,
                    request.getId(), new EmailNotification(recipient.email(),
                            NotificationType.SLA_BREACHED, variables).toPayload());
        }
    }

    private static Instant dueAtOf(Request request, Deadline deadline) {
        return switch (deadline) {
            case FIRST_RESPONSE -> request.getFirstResponseDueAt();
            case RESOLUTION -> request.getResolutionDueAt();
        };
    }

    /** Срок по часам арендатора, с названием пояса: «15.10.2026 18:00 (Europe/Moscow)». */
    private static String formatDueAt(Instant dueAt, ZoneId zone) {
        return dueAt.atZone(zone).format(DUE_AT) + " (" + zone.getId() + ")";
    }

    /** Имя необязательно — без него письмо обращается по адресу. */
    private static String nameOf(UserContactDto recipient) {
        return recipient.name() == null || recipient.name().isBlank()
                ? recipient.email() : recipient.name();
    }
}
