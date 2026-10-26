package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.common.model.RequestPriority;

class RequestSnapshotTest {

    private static final UUID TYPE_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID OPERATOR_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final Instant DUE_AT = Instant.parse("2026-10-01T09:00:00Z");

    private final Request request = new Request(UUID.randomUUID(), UUID.randomUUID(),
            "Выгрузка за квартал", "Нужен отчёт", RequestStatus.NEW);

    @Test
    void changesTo_whenNothingChanged_isEmpty() {
        final RequestSnapshot before = RequestSnapshot.of(request);

        assertThat(before.changesTo(RequestSnapshot.of(request))).isEmpty();
    }

    @Test
    void changesTo_whenEverySignificantFieldChanged_listsEachWithOldAndNewText() {
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.setPriority(RequestPriority.HIGH);
        request.setTypeId(TYPE_ID);
        request.setDesiredDueAt(DUE_AT);
        request.setDescription("Нужен отчёт с разбивкой по месяцам");
        request.changeAssignee(OPERATOR_ID, DUE_AT);

        assertThat(before.changesTo(RequestSnapshot.of(request))).containsExactly(
                new FieldChange(AuditedField.PRIORITY, RequestPriority.FALLBACK.name(), "HIGH"),
                new FieldChange(AuditedField.TYPE, null, TYPE_ID.toString()),
                new FieldChange(AuditedField.DESIRED_DUE_AT, null, "2026-10-01T09:00:00Z"),
                new FieldChange(AuditedField.DESCRIPTION, "Нужен отчёт",
                        "Нужен отчёт с разбивкой по месяцам"),
                new FieldChange(AuditedField.ASSIGNEE, null, OPERATOR_ID.toString()));
    }

    @Test
    void changesTo_whenFieldCleared_recordsEmptyNewValue() {
        request.changeAssignee(OPERATOR_ID, DUE_AT);
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.changeAssignee(null, DUE_AT);

        assertThat(before.changesTo(RequestSnapshot.of(request))).containsExactly(
                new FieldChange(AuditedField.ASSIGNEE, OPERATOR_ID.toString(), null));
    }

    /** T-062: пересчитанные сроки попадают в журнал — прежние и новые значения обоих. */
    @Test
    void changesTo_whenDeadlinesMoved_listsBothDeadlines() {
        request.setFirstResponseDueAt(DUE_AT);
        request.setResolutionDueAt(DUE_AT);
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.moveDeadlines(Instant.parse("2026-10-01T10:00:00Z"),
                Instant.parse("2026-10-02T09:00:00Z"), DUE_AT);

        assertThat(before.changesTo(RequestSnapshot.of(request))).containsExactly(
                new FieldChange(AuditedField.FIRST_RESPONSE_DUE_AT, "2026-10-01T09:00:00Z",
                        "2026-10-01T10:00:00Z"),
                new FieldChange(AuditedField.RESOLUTION_DUE_AT, "2026-10-01T09:00:00Z",
                        "2026-10-02T09:00:00Z"));
    }

    @Test
    void changesTo_whenOnlyInsignificantFieldsChanged_isEmpty() {
        final RequestSnapshot before = RequestSnapshot.of(request);
        request.setStatus(RequestStatus.IN_PROGRESS);
        request.setSubject("Другая тема");
        request.setResolutionOverdue(true);

        assertThat(before.changesTo(RequestSnapshot.of(request)))
                .as("статус пишется в историю статусов, остальное — не значимые поля")
                .isEmpty();
    }
}
