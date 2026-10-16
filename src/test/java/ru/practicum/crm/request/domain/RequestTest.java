package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.model.RequestPriority;

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
        assertThat(request.getPriority()).isEqualTo(RequestPriority.NORMAL);
        assertThat(request.getPriorityRank()).isEqualTo(RequestPriority.NORMAL.getRank());
        assertThat(request.getVersion()).isZero();
    }

    @Test
    void setters_whenValuesAssigned_areReturnedByGetters() {
        UUID typeId = UUID.randomUUID();
        Instant desired = Instant.parse("2026-10-01T09:00:00Z");
        Instant firstResponse = Instant.parse("2026-09-19T12:00:00Z");
        Instant resolution = Instant.parse("2026-09-25T12:00:00Z");

        request.setTypeId(typeId);
        request.setSubject("Новая тема");
        request.setDescription("Новое описание");
        request.setPriority(RequestPriority.HIGH);
        request.setStatus(RequestStatus.IN_PROGRESS);
        request.setDesiredDueAt(desired);
        request.setFirstResponseDueAt(firstResponse);
        request.setResolutionDueAt(resolution);
        request.setFirstResponseOverdue(true);
        request.setResolutionOverdue(true);
        request.setDeleted(true);

        assertThat(request.getTypeId()).isEqualTo(typeId);
        assertThat(request.getSubject()).isEqualTo("Новая тема");
        assertThat(request.getDescription()).isEqualTo("Новое описание");
        assertThat(request.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(request.getStatus()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(request.getDesiredDueAt()).isEqualTo(desired);
        assertThat(request.getFirstResponseDueAt()).isEqualTo(firstResponse);
        assertThat(request.getResolutionDueAt()).isEqualTo(resolution);
        assertThat(request.isFirstResponseOverdue()).isTrue();
        assertThat(request.isResolutionOverdue()).isTrue();
        assertThat(request.isDeleted()).isTrue();
    }

    /** Заявка просрочена, если истёк хотя бы один из сроков (T-060). */
    @Test
    void isOverdue_whenEitherDeadlineMissed_isTrue() {
        request.setFirstResponseOverdue(true);
        assertThat(request.isOverdue()).isTrue();

        request.setFirstResponseOverdue(false);
        request.setResolutionOverdue(true);
        assertThat(request.isOverdue()).isTrue();

        request.setResolutionOverdue(false);
        assertThat(request.isOverdue()).isFalse();
    }

    @Test
    void setPriority_whenChanged_movesRankTogetherWithIt() {
        request.setPriority(RequestPriority.URGENT);

        assertThat(request.getPriority()).isEqualTo(RequestPriority.URGENT);
        assertThat(request.getPriorityRank()).isEqualTo(RequestPriority.URGENT.getRank());
    }

    @Test
    void setPriority_whenNull_isRejectedAndPriorityStaysAsItWas() {
        request.setPriority(RequestPriority.HIGH);

        assertThatThrownBy(() -> request.setPriority(null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(request.getPriority()).isEqualTo(RequestPriority.HIGH);
        assertThat(request.getPriorityRank()).isEqualTo(RequestPriority.HIGH.getRank());
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

    @Test
    void changeAssignee_whenRequestHasNoAssigneeYet_setsAssigneeAndAssignedAtAndReturnsTrue() {
        UUID assigneeId = UUID.randomUUID();
        Instant occurredAt = Instant.parse("2026-10-13T10:00:00Z");

        boolean changed = request.changeAssignee(assigneeId, occurredAt);

        assertThat(changed).isTrue();
        assertThat(request.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(request.getAssignedAt()).isEqualTo(occurredAt);
    }

    @Test
    void changeAssignee_whenDifferentAssignee_reassignsAndMovesAssignedAt() {
        UUID firstAssignee = UUID.randomUUID();
        UUID secondAssignee = UUID.randomUUID();
        request.changeAssignee(firstAssignee, Instant.parse("2026-10-13T10:00:00Z"));

        Instant reassignedAt = Instant.parse("2026-10-14T08:30:00Z");
        boolean changed = request.changeAssignee(secondAssignee, reassignedAt);

        assertThat(changed).isTrue();
        assertThat(request.getAssigneeId()).isEqualTo(secondAssignee);
        assertThat(request.getAssignedAt()).isEqualTo(reassignedAt);
    }

    @Test
    void changeAssignee_whenSameAssigneeAsCurrent_isNoOpAndKeepsOriginalAssignedAt() {
        UUID assigneeId = UUID.randomUUID();
        Instant firstAssignedAt = Instant.parse("2026-10-13T10:00:00Z");
        request.changeAssignee(assigneeId, firstAssignedAt);

        boolean changed = request.changeAssignee(assigneeId, Instant.parse("2026-10-14T08:30:00Z"));

        assertThat(changed).isFalse();
        assertThat(request.getAssigneeId()).isEqualTo(assigneeId);
        assertThat(request.getAssignedAt()).isEqualTo(firstAssignedAt);
    }

    @Test
    void changeAssignee_whenNullAndRequestHadAssignee_clearsAssigneeAndAssignedAtAndReturnsTrue() {
        request.changeAssignee(UUID.randomUUID(), Instant.parse("2026-10-13T10:00:00Z"));

        boolean changed = request.changeAssignee(null, Instant.parse("2026-10-14T08:30:00Z"));

        assertThat(changed).isTrue();
        assertThat(request.getAssigneeId()).isNull();
        assertThat(request.getAssignedAt()).isNull();
    }

    @Test
    void changeAssignee_whenNullAndRequestAlreadyHadNoAssignee_isNoOpAndReturnsFalse() {
        boolean changed = request.changeAssignee(null, Instant.parse("2026-10-14T08:30:00Z"));

        assertThat(changed).isFalse();
        assertThat(request.getAssigneeId()).isNull();
        assertThat(request.getAssignedAt()).isNull();
    }

    @Test
    void changeAssignee_whenAssigneeGivenWithoutOccurredAt_isRejected() {
        assertThatThrownBy(() -> request.changeAssignee(UUID.randomUUID(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(request.getAssigneeId()).isNull();
        assertThat(request.getAssignedAt()).isNull();
    }
}
