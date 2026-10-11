package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.sla.api.RequestDeadlineCalculator;
import ru.practicum.crm.sla.api.RequestDeadlines;

class RequestDeadlineAssignerTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID TYPE_ID = UUID.randomUUID();
    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(2026, 10, 5, 14, 0, 0, 0, ZoneOffset.UTC);
    private static final RequestDeadlines DEADLINES = new RequestDeadlines(
            Instant.parse("2026-10-05T15:00:00Z"), Instant.parse("2026-10-06T13:00:00Z"));

    private final RequestDeadlineCalculator calculator = mock(RequestDeadlineCalculator.class);
    private final RequestDeadlineAssigner assigner = new RequestDeadlineAssigner(calculator);

    @Test
    void assignDeadlines_countsFromCreationAndSetsBothDeadlines() {
        Request request = savedRequest();
        when(calculator.calculate(TENANT_ID, TYPE_ID, RequestPriority.HIGH,
                CREATED_AT.toInstant())).thenReturn(Optional.of(DEADLINES));

        assigner.assignDeadlines(request);

        assertThat(request.getFirstResponseDueAt()).isEqualTo(DEADLINES.firstResponseDueAt());
        assertThat(request.getResolutionDueAt()).isEqualTo(DEADLINES.resolutionDueAt());
    }

    @Test
    void assignDeadlines_whenDeadlinesCannotBeCalculated_leavesRequestWithout() {
        Request request = savedRequest();
        when(calculator.calculate(TENANT_ID, TYPE_ID, RequestPriority.HIGH,
                CREATED_AT.toInstant())).thenReturn(Optional.empty());

        assigner.assignDeadlines(request);

        assertThat(request.getFirstResponseDueAt()).isNull();
        assertThat(request.getResolutionDueAt()).isNull();
    }

    @Test
    void assignDeadlines_forUnsavedRequest_isRejectedWithoutCalculation() {
        Request unsaved = new Request(TENANT_ID, UUID.randomUUID(), "Тема", "Описание",
                RequestStatus.NEW);

        assertThatThrownBy(() -> assigner.assignDeadlines(unsaved))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(calculator);
    }

    /** Заявка, как после сохранения: момент создания проставлен. */
    private static Request savedRequest() {
        Request request = new Request(TENANT_ID, UUID.randomUUID(), "Тема", "Описание",
                RequestStatus.NEW);
        request.setTypeId(TYPE_ID);
        request.setPriority(RequestPriority.HIGH);
        ReflectionTestUtils.setField(request, "createdAt", CREATED_AT);
        return request;
    }
}
