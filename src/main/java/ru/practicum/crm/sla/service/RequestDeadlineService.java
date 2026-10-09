package ru.practicum.crm.sla.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.sla.api.RequestDeadlineCalculator;
import ru.practicum.crm.sla.api.RequestDeadlines;
import ru.practicum.crm.sla.domain.SlaPolicy;
import ru.practicum.crm.sla.domain.SlaTerms;
import ru.practicum.crm.sla.domain.WorkingCalendar;

/**
 * Расчёт сроков заявки (T-058): политика для типа и приоритета, сложение её сроков по рабочему
 * календарю арендатора.
 *
 * <p>Ничего не бросает из-за настроек арендатора: по задаче заявка без сроков лучше, чем
 * несозданная заявка. Если сроки посчитать нельзя, причина пишется в лог: нет политики —
 * на уровне INFO (арендатор может сроки не настраивать), нет настроек или рабочего времени —
 * на уровне WARN (так быть не должно: их записывает создание арендатора).
 */
@Service
public class RequestDeadlineService implements RequestDeadlineCalculator {

    private static final Logger LOG = LoggerFactory.getLogger(RequestDeadlineService.class);

    private final SlaPolicyService policies;
    private final WorkingCalendarService calendars;

    public RequestDeadlineService(SlaPolicyService policies, WorkingCalendarService calendars) {
        this.policies = policies;
        this.calendars = calendars;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RequestDeadlines> calculate(UUID tenantId, UUID typeId,
            RequestPriority priority, Instant createdAt) {
        Optional<SlaPolicy> policy = policies.resolve(tenantId, typeId, priority);
        if (policy.isEmpty()) {
            LOG.info("Сроки заявки не проставлены: у арендатора {} нет политики для типа {} и"
                    + " приоритета {} и нет политики по умолчанию", value("tenantId", tenantId),
                    value("typeId", typeId), value("priority", priority));
            return Optional.empty();
        }
        Optional<WorkingCalendar> calendar = calendars.findCalendar(tenantId);
        if (calendar.isEmpty()) {
            LOG.warn("Сроки заявки не проставлены: у арендатора {} нет настроек, часовой пояс"
                    + " неизвестен", value("tenantId", tenantId));
            return Optional.empty();
        }
        SlaTerms terms = policy.get().getTerms();
        Optional<Instant> firstResponse = calendar.get()
                .plusWorkingMinutes(createdAt, terms.firstResponseMinutes());
        Optional<Instant> resolution = calendar.get()
                .plusWorkingMinutes(createdAt, terms.resolutionMinutes());
        if (firstResponse.isEmpty() || resolution.isEmpty()) {
            LOG.warn("Сроки заявки не проставлены: в календаре арендатора {} нет рабочего времени",
                    value("tenantId", tenantId));
            return Optional.empty();
        }
        return Optional.of(new RequestDeadlines(firstResponse.get(), resolution.get()));
    }
}
