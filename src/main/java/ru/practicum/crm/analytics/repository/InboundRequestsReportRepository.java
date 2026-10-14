package ru.practicum.crm.analytics.repository;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import ru.practicum.crm.analytics.api.dto.InboundPoint;
import ru.practicum.crm.analytics.api.dto.InboundSeries;
import ru.practicum.crm.analytics.domain.InboundReportQuery;

/**
 * Агрегат поступления заявок по дням.
 */
@Repository
@RequiredArgsConstructor
public class InboundRequestsReportRepository {

    private final JdbcTemplate jdbcTemplate;

    private enum GroupAxis {
        TYPE("r.type_id", "type_id"),
        PRIORITY("r.priority", "priority");

        private final String expression;
        private final String alias;

        GroupAxis(String expression, String alias) {
            this.expression = expression;
            this.alias = alias;
        }
    }

    public List<InboundSeries> aggregate(InboundReportQuery query) {

        return switch (query.groupBy()) {
            case NONE -> List.of(singleSeries(query));
            case TYPE -> groupedSeries(query, GroupAxis.TYPE);
            case PRIORITY -> groupedSeries(query, GroupAxis.PRIORITY);
        };
    }

    private InboundSeries singleSeries(InboundReportQuery query) {

        final String sql =
                """
                WITH days AS (
                    SELECT generate_series(?::date, ?::date, interval '1 day')::date AS day
                ),
                agg AS (
                    SELECT (r.created_at AT TIME ZONE ?)::date AS day,
                           COUNT(*)::BIGINT AS cnt
                    FROM requests AS r
                    WHERE r.tenant_id = ?
                        AND NOT r.deleted
                        AND r.created_at >= ?
                        AND r.created_at < ?
                        %s
                    GROUP BY 1
                )
                SELECT d.day, COALESCE(a.cnt, 0) AS cnt
                FROM days AS d
                LEFT JOIN agg AS a ON a.day = d.day
                ORDER BY d.day
                """.formatted(query.filterClause());

        List<Object> args = query.baseArgs();

        List<InboundPoint> points = jdbcTemplate.query(
                sql,
                (rs, rowNum) -> new InboundPoint(
                        rs.getObject("day", LocalDate.class), rs.getLong("cnt")),
                args.toArray());

        return new InboundSeries(null, List.copyOf(points));
    }

    private List<InboundSeries> groupedSeries(
            InboundReportQuery query, GroupAxis axis) {

        final String sql =
                """
                WITH days AS (
                    SELECT generate_series(?::date, ?::date, interval '1 day')::date AS day
                ),
                agg AS (
                    SELECT (r.created_at AT TIME ZONE ?)::date AS day,
                           %s AS %s,
                           COUNT(*)::BIGINT AS cnt
                    FROM requests AS r
                    WHERE r.tenant_id = ?
                        AND NOT r.deleted
                        AND r.created_at >= ?
                        AND r.created_at < ?
                        %s
                    GROUP BY 1, 2
                ),
                keys AS (
                    SELECT DISTINCT %s AS %s FROM agg
                )
                SELECT k.%s AS series_key,
                    d.day, COALESCE(a.cnt, 0) AS cnt
                FROM keys AS k
                CROSS JOIN days AS d
                LEFT JOIN agg AS a ON a.day = d.day AND a.%s IS NOT DISTINCT FROM k.%s
                ORDER BY k.%s NULLS FIRST, d.day
                """.formatted(
                        axis.expression, axis.alias,
                        query.filterClause(),
                        axis.alias, axis.alias,
                        axis.alias,
                        axis.alias, axis.alias,
                        axis.alias
                );

        List<Object> args = query.baseArgs();

        Map<String, List<InboundPoint>> byKey = new LinkedHashMap<>();
        jdbcTemplate.query(sql, rs -> {
            Object rawKey = rs.getObject("series_key");
            String key = rawKey == null ? null : rawKey.toString();
            byKey.computeIfAbsent(key, ignored -> new ArrayList<>())
                    .add(new InboundPoint(
                            rs.getObject("day", LocalDate.class), rs.getLong("cnt")
                    ));
        }, args.toArray());

        List<InboundSeries> series = new ArrayList<>(byKey.size());
        for (Map.Entry<String, List<InboundPoint>> entry : byKey.entrySet()) {
            series.add(new InboundSeries(entry.getKey(), List.copyOf(entry.getValue())));
        }

        return series;
    }
}
