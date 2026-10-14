package ru.practicum.crm.analytics.service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.analytics.api.InboundGroupBy;
import ru.practicum.crm.analytics.api.dto.InboundByDayReportResponse;
import ru.practicum.crm.analytics.config.AnalyticsProperties;
import ru.practicum.crm.analytics.domain.InboundReportQuery;
import ru.practicum.crm.analytics.repository.InboundRequestsReportRepository;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;

@Service
@RequiredArgsConstructor
public class InboundRequestReportService {

    private final TenantContext tenantContext;
    private final TenantTimezoneProvider timezoneProvider;
    private final AnalyticsProperties analyticsProperties;
    private final InboundRequestsReportRepository inboundRequestsReportRepository;

    /**
     * Строит отчет для текущего арендатора.
     *
     * @param from первый день переода включительно
     * @param to последний день переода включительно
     * @param groupByRaw группировка по {@code none|type|priority};
     * @param typeId фильтр по типу заявки (если параметр присутствует)
     * @param priorityRaw фильтр по приоритету (если параметр присутствует)
     * */
    @Transactional(readOnly = true)
    public InboundByDayReportResponse report(
            LocalDate from, LocalDate to, String groupByRaw, UUID typeId, String priorityRaw) {

        validatePeriod(from, to);
        InboundGroupBy groupBy = InboundGroupBy.fromParam(groupByRaw);
        RequestPriority priority = parsePriority(priorityRaw);

        UUID tenantId = tenantContext.getCurrentTenantId();
        ZoneId zone = timezoneProvider.timezoneOf(tenantId);

        return new InboundByDayReportResponse(
                inboundRequestsReportRepository.aggregate(
                        new InboundReportQuery(tenantId, from, to, zone, typeId, priority, groupBy)
                )
        );
    }

    private void validatePeriod(LocalDate from, LocalDate to) {
        List<ValidationError> errors = new ArrayList<>();

        if (from == null) {
            errors.add(ValidationError.ofParameter("from", "Не указанно начало периода"));
        }

        if (to == null) {
            errors.add(ValidationError.ofParameter("to", "Не указанно конец периода"));
        }

        if (from != null && to != null) {
            if (to.isBefore(from)) {
                errors.add(ValidationError.ofParameter(
                        "to", "Конеч периода не должен быть раньше начало"));
            } else {
                long length = ChronoUnit.DAYS.between(from, to) + 1;
                int max = analyticsProperties.maxPeriodDays();
                if (length > max) {
                    errors.add(ValidationError.ofParameter(
                            "to", "Период не должен превышать %s дней".formatted(max)));
                }
            }
        }

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "Испраьте параметры периода", errors);
        }
    }

    private RequestPriority parsePriority(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return RequestPriority.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new ApiException(
                    ErrorCode.VALIDATION_FAILED,
                    "Исправьте параметры отчета.",
                    List.of(ValidationError.ofParameter(
                            "priority", "Неизвестный приоритет"
                    ))
            );
        }
    }
}
