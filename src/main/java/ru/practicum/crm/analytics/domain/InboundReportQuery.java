package ru.practicum.crm.analytics.domain;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import ru.practicum.crm.analytics.api.InboundGroupBy;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Параметры агрегата поступления заявок.
 */
public record InboundReportQuery(
        UUID tenantId,
        LocalDate from,
        LocalDate to,
        ZoneId zone,
        UUID typeId,
        RequestPriority priority,
        InboundGroupBy groupBy
) {

    public Instant fromStart() {
        return from.atStartOfDay(zone).toInstant();
    }

    public Instant toEnd() {
        return to.plusDays(1).atStartOfDay(zone).toInstant();
    }

    /**
     * Базовые аргиументы SQL и опцианальные фильтры.*/
    public List<Object> baseArgs() {
        List<Object> args = new ArrayList<>();
        args.add(Date.valueOf(from));
        args.add(Date.valueOf(to));
        args.add(zone.getId());
        args.add(tenantId);
        args.add(Timestamp.from(fromStart()));
        args.add(Timestamp.from(toEnd()));

        if (typeId != null) {
            args.add(typeId);
        }
        if (priority != null) {
            args.add(priority.name());
        }

        return args;
    }

    /**
     * Условаия фильтра для подстановки в {@code WHERE} после базовых предикатов.
     */
    public String filterClause() {
        StringBuilder clause = new StringBuilder();
        if (typeId != null) {
            clause.append(" AND r.type_id = ?");
        }
        if (priority != null) {
            clause.append(" AND r.priority = ?");
        }
        return clause.toString();
    }
}
