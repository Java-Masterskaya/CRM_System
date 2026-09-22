package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class RequestTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    private Request request;

    @BeforeEach
    void setUp() {
        request = new Request(TENANT_ID, AUTHOR_ID, "Выгрузка отчёта", "Нужен отчёт за квартал",
                RequestStatus.NEW);
    }

    @Test
    void constructor_whenCalled_fillsFieldsWithoutWhichRequestIsMeaningless() {
        assertThat(request.getTenantId()).isEqualTo(TENANT_ID);
        assertThat(request.getAuthorId()).isEqualTo(AUTHOR_ID);
        assertThat(request.getSubject()).isEqualTo("Выгрузка отчёта");
        assertThat(request.getDescription()).isEqualTo("Нужен отчёт за квартал");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.NEW);
    }

    @Test
    void newRequest_whenCreated_isNeitherOverdueNorDeletedAndHasNoAssignee() {
        assertThat(request.isOverdue()).isFalse();
        assertThat(request.isDeleted()).isFalse();
        assertThat(request.getAssigneeId()).isNull();
        assertThat(request.getTypeId()).isNull();
        assertThat(request.getPriority()).isNull();
        assertThat(request.getVersion()).isZero();
    }

    @Test
    void setters_whenValuesAssigned_areReturnedByGetters() {
        UUID typeId = UUID.randomUUID();
        UUID assigneeId = UUID.randomUUID();
        Instant desired = Instant.parse("2026-10-01T09:00:00Z");
        Instant firstResponse = Instant.parse("2026-09-19T12:00:00Z");
        Instant resolution = Instant.parse("2026-09-25T12:00:00Z");

        request.setTypeId(typeId);
        request.setAssigneeId(assigneeId);
        request.setSubject("Новая тема");
        request.setDescription("Новое описание");
        request.setPriority("HIGH");
        request.setStatus(RequestStatus.IN_PROGRESS);
        request.setDesiredDueAt(desired);
        request.setFirstResponseDueAt(firstResponse);
        request.setResolutionDueAt(resolution);
        request.setOverdue(true);
        request.setDeleted(true);

        assertThat(request.getTypeId()).isEqualTo(typeId);
        assertThat(request.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(request.getSubject()).isEqualTo("Новая тема");
        assertThat(request.getDescription()).isEqualTo("Новое описание");
        assertThat(request.getPriority()).isEqualTo("HIGH");
        assertThat(request.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(request.getDesiredDueAt()).isEqualTo(desired);
        assertThat(request.getFirstResponseDueAt()).isEqualTo(firstResponse);
        assertThat(request.getResolutionDueAt()).isEqualTo(resolution);
        assertThat(request.isOverdue()).isTrue();
        assertThat(request.isDeleted()).isTrue();
    }

    @Test
    void dataParams_whenSourceMapChangedAfterAssignment_requestKeepsOriginalValues() {
        Map<String, Object> source = new HashMap<>();
        source.put("format", "csv");
        request.setDataParams(source);

        source.put("format", "xlsx");

        assertThat(request.getDataParams()).containsExactly(Map.entry("format", "csv"));
    }

    @Test
    void dataParams_whenReturnedMapChanged_requestKeepsOriginalValues() {
        request.setDataParams(Map.of("format", "csv"));

        request.getDataParams().put("format", "xlsx");

        assertThat(request.getDataParams()).containsExactly(Map.entry("format", "csv"));
    }

    @Test
    void dataParams_whenNotProvided_stayNull() {
        request.setDataParams(null);

        assertThat(request.getDataParams()).isNull();
    }

    @Test
    void equals_whenRequestsAreNotSavedYet_distinguishesThemByIdentity() {
        Request other = new Request(TENANT_ID, AUTHOR_ID, "Выгрузка отчёта",
                "Нужен отчёт за квартал", RequestStatus.NEW);

        assertThat(request).isEqualTo(request)
                .isNotEqualTo(other)
                .isNotEqualTo(null)
                .isNotEqualTo("не заявка");
        assertThat(request.hashCode()).isEqualTo(other.hashCode());
    }

    @Test
    void statuses_whenChecked_markOnlyFinishedOnesAsTerminal() {
        assertThat(RequestStatus.DONE.isTerminal()).isTrue();
        assertThat(RequestStatus.REJECTED.isTerminal()).isTrue();
        assertThat(RequestStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(RequestStatus.NEW.isTerminal()).isFalse();
        assertThat(RequestStatus.CONTACTED.isTerminal()).isFalse();
        assertThat(RequestStatus.IN_PROGRESS.isTerminal()).isFalse();
        assertThat(RequestStatus.ON_HOLD.isTerminal()).isFalse();
    }
}
