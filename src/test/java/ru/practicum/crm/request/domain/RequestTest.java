package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.model.RequestPriority;

class RequestTest {

    private static final UUID TENANT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID AUTHOR_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant NOW = Instant.parse("2026-10-25T12:00:00Z");
    private static final Instant EARLIER = NOW.minusSeconds(3600);
    private static final Instant LATER = NOW.plusSeconds(3600);
    private static final Instant MUCH_LATER = NOW.plusSeconds(8 * 3600);

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
        assertThat(request.getDeletedAt()).isNull();
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

    /** Заявка в NEW просрочена, если истёк хотя бы один из сроков (T-060). */
    @Test
    void isOverdue_whenEitherDeadlineMissedInNew_isTrue() {
        request.setFirstResponseOverdue(true);
        assertThat(request.isOverdue()).isTrue();

        request.setFirstResponseOverdue(false);
        request.setResolutionOverdue(true);
        assertThat(request.isOverdue()).isTrue();

        request.setResolutionOverdue(false);
        assertThat(request.isOverdue()).isFalse();
    }

    /**
     * Просрочка считается только по тем срокам, что ещё идут для статуса заявки (T-040, ревью
     * #199): срок первого ответа — в NEW, срок решения — до завершения. Признаки при этом
     * остаются: закрытая с нарушением заявка — не «просроченная», но и не «закрытая в срок».
     */
    @ParameterizedTest
    @CsvSource({
        "NEW, true, false, true",
        "CONTACTED, true, false, false",
        "CONTACTED, false, true, true",
        "IN_PROGRESS, false, true, true",
        "ON_HOLD, false, true, true",
        "DONE, true, true, false",
        "REJECTED, false, true, false",
        "CANCELLED, true, true, false"
    })
    void isOverdue_countsOnlyDeadlinesStillRunningForStatus(RequestStatus status,
            boolean firstResponseOverdue, boolean resolutionOverdue, boolean expected) {
        request.setStatus(status);
        request.setFirstResponseOverdue(firstResponseOverdue);
        request.setResolutionOverdue(resolutionOverdue);

        assertThat(request.isOverdue()).isEqualTo(expected);
        assertThat(request.isFirstResponseOverdue()).isEqualTo(firstResponseOverdue);
        assertThat(request.isResolutionOverdue()).isEqualTo(resolutionOverdue);
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
    void assignmentWait_whenUnassigned_isTimeSinceCreation() {
        Instant created = Instant.parse("2026-10-27T10:00:00Z");
        Instant now = Instant.parse("2026-10-29T16:00:00Z");
        ReflectionTestUtils.setField(request, "createdAt",
                OffsetDateTime.ofInstant(created, ZoneOffset.UTC));

        assertThat(request.assignmentWait(now)).isEqualTo(Duration.ofHours(54));
    }

    @Test
    void assignmentWait_whenAssigned_isNull() {
        Instant created = Instant.parse("2026-10-27T10:00:00Z");
        Instant now = Instant.parse("2026-10-29T16:00:00Z");
        ReflectionTestUtils.setField(request, "createdAt",
                OffsetDateTime.ofInstant(created, ZoneOffset.UTC));

        request.changeAssignee(UUID.randomUUID(), Instant.parse("2026-10-27T10:00:00Z"));
        assertThat(request.assignmentWait(now)).isNull();
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

    /**
     * #200: оба срока перенесены в будущее — признаки просрочки сняты, и вызывающему сказано
     * снять отметки эскалации у обоих.
     */
    @Test
    void moveDeadlines_whenNewDeadlinesAhead_reopensBothOverdueDeadlines() {
        Request overdue = overdueRequest(RequestStatus.NEW);

        ReopenedDeadlines reopened = overdue.moveDeadlines(LATER, MUCH_LATER, NOW);

        assertThat(reopened).isEqualTo(new ReopenedDeadlines(true, true));
        assertThat(overdue.getFirstResponseDueAt()).isEqualTo(LATER);
        assertThat(overdue.getResolutionDueAt()).isEqualTo(MUCH_LATER);
        assertThat(overdue.isFirstResponseOverdue()).isFalse();
        assertThat(overdue.isResolutionOverdue()).isFalse();
        assertThat(overdue.isOverdue()).isFalse();
    }

    /** Новый срок тоже уже прошёл — нарушение остаётся нарушением, эскалация не повторяется. */
    @Test
    void moveDeadlines_whenNewDeadlineAlsoPassed_keepsItsOverdueFlag() {
        Request overdue = overdueRequest(RequestStatus.NEW);

        ReopenedDeadlines reopened = overdue.moveDeadlines(EARLIER, MUCH_LATER, NOW);

        assertThat(reopened).isEqualTo(new ReopenedDeadlines(false, true));
        assertThat(overdue.getFirstResponseDueAt()).isEqualTo(EARLIER);
        assertThat(overdue.isFirstResponseOverdue()).isTrue();
    }

    /**
     * Ответ уже дан: срок первого ответа сыграл и не переносится, его признак — факт о том, как
     * ответили. Срок решения ещё считается и переносится.
     */
    @Test
    void moveDeadlines_afterFirstResponse_movesOnlyResolution() {
        Request answered = overdueRequest(RequestStatus.CONTACTED);

        ReopenedDeadlines reopened = answered.moveDeadlines(LATER, MUCH_LATER, NOW);

        assertThat(reopened).isEqualTo(new ReopenedDeadlines(false, true));
        assertThat(answered.getFirstResponseDueAt()).isEqualTo(EARLIER);
        assertThat(answered.isFirstResponseOverdue()).isTrue();
        assertThat(answered.getResolutionDueAt()).isEqualTo(MUCH_LATER);
    }

    /** Признака не было — снимать нечего, хотя срок перенесён. */
    @Test
    void moveDeadlines_whenNothingWasOverdue_reopensNothing() {
        Request onTime = new Request(UUID.randomUUID(), UUID.randomUUID(), "Тема", "Описание",
                RequestStatus.NEW);

        assertThat(onTime.moveDeadlines(LATER, MUCH_LATER, NOW).any()).isFalse();
        assertThat(onTime.getResolutionDueAt()).isEqualTo(MUCH_LATER);
    }

    /** Заявка, по которой оба срока истекли и помечены фоновой проверкой. */
    private static Request overdueRequest(RequestStatus status) {
        Request overdue = new Request(UUID.randomUUID(), UUID.randomUUID(), "Тема", "Описание",
                status);
        overdue.setFirstResponseDueAt(EARLIER);
        overdue.setResolutionDueAt(EARLIER);
        overdue.setFirstResponseOverdue(true);
        overdue.setResolutionOverdue(true);
        return overdue;
    }
}
