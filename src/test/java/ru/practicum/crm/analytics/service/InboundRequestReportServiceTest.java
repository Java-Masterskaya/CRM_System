package ru.practicum.crm.analytics.service;

import static org.assertj.core.api.AssertionsForClassTypes.catchThrowableOfType;
import static org.assertj.core.api.AssertionsForInterfaceTypes.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import ru.practicum.crm.analytics.api.InboundGroupBy;
import ru.practicum.crm.analytics.api.dto.InboundByDayReportResponse;
import ru.practicum.crm.analytics.api.dto.InboundPoint;
import ru.practicum.crm.analytics.api.dto.InboundSeries;
import ru.practicum.crm.analytics.config.AnalyticsProperties;
import ru.practicum.crm.analytics.domain.InboundReportQuery;
import ru.practicum.crm.analytics.repository.InboundRequestsReportRepository;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.tenant.api.TenantContext;
import ru.practicum.crm.tenant.api.TenantTimezoneProvider;

class InboundRequestReportServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TYPE_ID = UUID.randomUUID();
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final LocalDate FROM = LocalDate.of(2026, 1, 1);
    private static final LocalDate TO = LocalDate.of(2026, 1, 10);

    private final InboundRequestsReportRepository repository =
            mock(InboundRequestsReportRepository.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final TenantTimezoneProvider timezone = mock(TenantTimezoneProvider.class);
    private final AnalyticsProperties properties = new AnalyticsProperties(30);

    private final InboundRequestReportService service = new InboundRequestReportService(
            tenantContext, timezone, properties, repository);

    @BeforeEach
    void bindTenant() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(timezone.timezoneOf(TENANT_ID)).thenReturn(MOSCOW);
    }

    @Test
    void report_whenDefaults_queriesSingleSeriesWithoutFilters() {
        when(repository.aggregate(any())).thenReturn(List.of(new InboundSeries(
                null,
                List.of(new InboundPoint(FROM, 0))
        )));

        InboundByDayReportResponse response =
                service.report(FROM, TO, "none", null, null);

        assertThat(response.series()).hasSize(1);
        assertThat(response.series().getFirst().key()).isNull();

        ArgumentCaptor<InboundReportQuery> captor =
                ArgumentCaptor.forClass(InboundReportQuery.class);

        verify(repository).aggregate(captor.capture());
        InboundReportQuery query = captor.getValue();
        assertThat(query.tenantId()).isEqualTo(TENANT_ID);
        assertThat(query.from()).isEqualTo(FROM);
        assertThat(query.to()).isEqualTo(TO);
        assertThat(query.zone()).isEqualTo(MOSCOW);
        assertThat(query.typeId()).isNull();
        assertThat(query.priority()).isNull();
        assertThat(query.groupBy()).isEqualTo(InboundGroupBy.NONE);
        assertThat(query.fromStart()).isEqualTo(FROM.atStartOfDay(MOSCOW).toInstant());
        assertThat(query.toEnd()).isEqualTo(TO.plusDays(1).atStartOfDay(MOSCOW).toInstant());
    }

    @Test
    void report_whenFiltersAndGroupByType_passesThemThrough() {
        when(repository.aggregate(any())).thenReturn(List.of());

        service.report(FROM, TO, "type", TYPE_ID, "high");

        ArgumentCaptor<InboundReportQuery> captor =
                ArgumentCaptor.forClass(InboundReportQuery.class);
        verify(repository).aggregate(captor.capture());
        assertThat(captor.getValue()).isEqualTo(new InboundReportQuery(
                TENANT_ID, FROM, TO, MOSCOW, TYPE_ID, RequestPriority.HIGH, InboundGroupBy.TYPE
        ));
    }

    @Test
    void report_whenPeriodLongerThanLimit_returnsValidationError() {
        ApiException exception = catchThrowableOfType(
                () -> service.report(FROM, FROM.plusDays(
                        properties.maxPeriodDays()), null, null, null),
                ApiException.class
        );

        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getErrors()).containsExactly(
                ValidationError.ofParameter("to",
                        "Период не должен превышать %s дней".formatted(properties.maxPeriodDays()))
        );
        verify(repository, never()).aggregate(any());
    }

    @Test
    void report_whenGroupByUnknown_returnsValidationError() {
        ApiException exception = catchThrowableOfType(
                () -> service.report(FROM, TO, "type, priority", null, null),
                ApiException.class
        );
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getErrors()).extracting(ValidationError::parameter)
                .containsExactly("groupBy");
    }

    @Test
    void report_whenPriorityUnknown_returnsValidationError() {
        ApiException exception = catchThrowableOfType(
                () -> service.report(FROM, TO, null, null, "TEST"),
                ApiException.class
        );
        assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(exception.getErrors()).extracting(ValidationError::parameter)
                .containsExactly("priority");
    }
}
