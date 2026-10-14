package ru.practicum.crm.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.analytics.api.dto.InboundByDayReportResponse;
import ru.practicum.crm.analytics.api.dto.InboundPoint;
import ru.practicum.crm.analytics.api.dto.InboundSeries;
import ru.practicum.crm.analytics.repository.InboundRequestsReportRepository;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.tenant.api.TenantContext;

class InboundRequestsReportIntegrationTest extends BaseIntegrationTest {

    private static final String TIMEZONE = "Europe/Moscow";
    private static final ZoneId MOSCOW = ZoneId.of(TIMEZONE);
    private static final LocalDate FROM = LocalDate.of(2026, 3, 1);
    private static final LocalDate TO = LocalDate.of(2026, 3, 5);

    @Autowired
    private InboundRequestReportService service;

    @Autowired
    private InboundRequestsReportRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @MockBean
    private TenantContext tenantContext;

    private UUID tenantA;
    private UUID tenantB;
    private UUID authorId;
    private UUID typeExport;
    private UUID typeImport;
    private UUID typeUnused;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant("Арендатор А", TIMEZONE);
        tenantB = insertTenant("Арендатор Б", TIMEZONE);
        authorId = UUID.randomUUID();
        typeExport = insertType(tenantA, "Выгрузка");
        typeImport = insertType(tenantA, "Загрузка");
        typeUnused = insertType(tenantA, "Без заявок");
        when(tenantContext.getCurrentTenantId()).thenReturn(tenantA);
    }

    @Test
    void report_whenGroupByNone_returnsOneSeriesWithZerosForEmptyDays() {
        insertRequest(tenantA, typeExport, RequestPriority.HIGH,
                Instant.parse("2026-03-01T10:00:00Z"));
        insertRequest(tenantA, typeExport, RequestPriority.LOW,
                Instant.parse("2026-03-01T19:00:00Z"));
        insertRequest(tenantA, typeImport, RequestPriority.NORMAL,
                Instant.parse("2026-03-05T12:00:00Z"));

        InboundByDayReportResponse response =
                service.report(FROM, TO, null, null, null);

        assertThat(response.series()).hasSize(1);
        InboundSeries series = response.series().getFirst();
        assertThat(series.key()).isNull();
        assertThat(series.points()).extracting(InboundPoint::date)
                .containsExactly(
                        LocalDate.of(2026, 3, 1),
                        LocalDate.of(2026, 3, 2),
                        LocalDate.of(2026, 3, 3),
                        LocalDate.of(2026, 3, 4),
                        LocalDate.of(2026, 3, 5));
        assertThat(series.points()).extracting(InboundPoint::count)
                .containsExactly(2L, 0L, 1L, 0L, 0L);
        assertThat(sum(response)).isEqualTo(countRequests(tenantA, FROM, TO, null, null));
    }

    @Test
    void report_whenGroupByType_createsSeriesOnlyForTypesWithData() {
        insertRequest(tenantA, typeExport, RequestPriority.HIGH,
                Instant.parse("2026-03-01T10:00:00Z"));
        insertRequest(tenantA, typeExport, RequestPriority.NORMAL,
                Instant.parse("2026-03-05T10:00:00Z"));
        insertRequest(tenantA, typeImport, RequestPriority.LOW,
                Instant.parse("2026-03-01T12:00:00Z"));

        InboundByDayReportResponse response =
                service.report(FROM, TO, "type", null, null);

        assertThat(response.series()).extracting(InboundSeries::key)
                .containsExactlyInAnyOrder(typeExport.toString(), typeExport.toString())
                .doesNotContain(typeUnused.toString());
        InboundSeries exported = seriesByKey(response, typeExport.toString());
        assertThat(exported.points()).extracting(InboundPoint::count)
                .containsExactly(1L, 0L, 1L, 0L, 0L);

        InboundSeries imported = seriesByKey(response, typeExport.toString());
        assertThat(imported.points()).extracting(InboundPoint::count)
                .containsExactly(1L, 0L, 0L, 0L, 0L);

        assertThat(sum(response)).isEqualTo(3L);
    }

    @Test
    void report_whenGroupByPriority_splitsOnlySeenPriorities() {
        insertRequest(tenantA, typeExport, RequestPriority.HIGH,
                Instant.parse("2026-03-01T10:00:00Z"));
        insertRequest(tenantA, typeExport, RequestPriority.HIGH,
                Instant.parse("2026-03-02T10:00:00Z"));
        insertRequest(tenantA, typeImport, RequestPriority.LOW,
                Instant.parse("2026-03-01T12:00:00Z"));

        InboundByDayReportResponse response =
                service.report(FROM, TO, "priority", null, null);

        assertThat(response.series()).extracting(InboundSeries::key)
                .containsExactlyInAnyOrder("HIGH", "LOW")
                .doesNotContain("NORMAL", "URGENT");
        assertThat(sum(response)).isEqualTo(3L);
    }

    @Test
    void report_whenFilteredByTypeAndPriority_narrowsBeforeGrouping() {
        insertRequest(tenantA, typeExport, RequestPriority.HIGH,
                Instant.parse("2026-03-01T10:00:00Z"));
        insertRequest(tenantA, typeExport, RequestPriority.LOW,
                Instant.parse("2026-03-01T11:00:00Z"));
        insertRequest(tenantA, typeImport, RequestPriority.HIGH,
                Instant.parse("2026-03-01T12:00:00Z"));

        InboundByDayReportResponse response =
                service.report(FROM, TO, null, typeExport, "HIGH");

        assertThat(response.series()).hasSize(1);
        assertThat(response.series().getFirst().points().getFirst().count()).isEqualTo(1L);
        assertThat(sum(response)).isEqualTo(
                countRequests(tenantA, FROM, TO, typeExport, RequestPriority.HIGH)
        );
    }

    @Test
    void report_whenCreatedNearMidnightInTenantZone_countsOnLocalDate() {
        insertRequest(tenantA, typeExport, RequestPriority.NORMAL,
                Instant.parse("2026-03-01T20:59:59Z"));
        insertRequest(tenantA, typeExport, RequestPriority.NORMAL,
                Instant.parse("2026-03-01T21:00:00Z"));

        InboundByDayReportResponse response =
                service.report(FROM, TO, null, null, null);

        List<InboundPoint> points = response.series().getFirst().points();
        assertThat(points).filteredOn(p -> p.date().equals(LocalDate.of(2026, 3, 1)))
                .extracting(InboundPoint::count).containsExactly(1L);
        assertThat(points).filteredOn(p -> p.date().equals(LocalDate.of(2026, 3, 2)))
                .extracting(InboundPoint::count).containsExactly(1L);
    }

    @Test
    void report_whenOtherTenantHasRequests_doesNotIncludeThem() {
        insertRequest(tenantA, typeExport, RequestPriority.NORMAL,
                Instant.parse("2026-03-01T10:00:00Z"));
        insertRequest(tenantB, insertType(tenantB, "Чужой тип"), RequestPriority.URGENT,
                Instant.parse("2026-03-01T10:00:00Z"));

        InboundByDayReportResponse response =
                service.report(FROM, TO, null, null, null);

        assertThat(sum(response)).isEqualTo(1L);
    }

    @Test
    void report_whenPeriodTooLong_isRejected() {
        assertThatThrownBy(() -> service.report(FROM, FROM.plusDays(31), null, null, null))
                .isInstanceOf(ApiException.class)
                .extracting(ex -> ((ApiException) ex).getErrorCode())
                .isEqualTo(ErrorCode.VALIDATION_FAILED);
    }

    @Test
    void report_whenDeletedRequest_isExcluded() {
        insertRequest(tenantA, typeExport, RequestPriority.NORMAL,
                Instant.parse("2026-03-01T10:00:00Z"));
        UUID deleted = insertRequest(tenantA, typeExport, RequestPriority.NORMAL,
                Instant.parse("2026-03-01T11:00:00Z"));
        jdbcTemplate.update("UPDATE requests SET deleted = TRUE WHERE id = ?", deleted);

        assertThat(sum(service.report(FROM, TO, null, null, null)))
                .isEqualTo(1);
    }

    private static long sum(InboundByDayReportResponse response) {
        return response.series().stream()
                .flatMap(s -> s.points().stream())
                .mapToLong(InboundPoint::count)
                .sum();
    }

    private static InboundSeries seriesByKey(InboundByDayReportResponse response, String key) {
        return response.series().stream()
                .filter(s -> key.equals(s.key()))
                .findFirst()
                .orElseThrow();
    }

    private long countRequests(
            UUID tenantId, LocalDate from, LocalDate to, UUID typeId, RequestPriority priority
    ) {
        Instant fromStart = from.atStartOfDay(MOSCOW).toInstant();
        Instant toEnd = to.plusDays(1).atStartOfDay(MOSCOW).toInstant();

        ArrayList<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(Timestamp.from(fromStart));
        args.add(Timestamp.from(toEnd));

        StringBuilder sql = new StringBuilder(
                """
                SELECT COUNT(*) FROM requests
                WHERE tenant_id = ?
                    AND NOT deleted
                    AND created_at >= ?
                    AND created_at < ?
                """
        );

        if (typeId != null) {
            sql.append(" AND r.type_id = ?");
            args.add(typeId);
        }
        if (priority != null) {
            sql.append(" AND r.priority = ?");
            args.add(priority);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    private UUID insertTenant(String name, String timezone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                        + " VALUES (?, ?, ?, true, now(), now())",
                id, name, "tenant-" + id);

        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, ?, now(), now())", id, timezone);
        return id;
    }

    private UUID insertType(UUID tenantId, String name) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO request_types (id, tenant_id, name, active, created_at, updated_at)"
                        + "VALUES (?, ?, 'Выгрузка', true, now(), now())", id, tenantId, name);
        return id;
    }

    private UUID insertRequest(
            UUID tenantId, UUID typeId, RequestPriority priority, Instant createdAt) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO requests (id, tenant_id, type_id, subject, description, status,"
                        + " priority, priority_rank, author_id, created_at, updated_at)"
                        + " VALUES (?, ?, ?, 'Выгрузка', 'Нужен отчёт', 'NEW', ?, ?, ?, ?, ?)",
                id, tenantId, typeId, priority.name(), priority.getRank(), authorId,
                Timestamp.from(createdAt), Timestamp.from(createdAt));

        return id;
    }
}
