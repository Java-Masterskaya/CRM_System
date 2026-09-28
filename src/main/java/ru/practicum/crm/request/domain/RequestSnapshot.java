package ru.practicum.crm.request.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Значения значимых полей заявки в какой-то момент — чтобы после правки узнать, что изменилось.
 *
 * <p>Снимок делается до правки ({@link #of}) и сравнивается с состоянием после неё
 * ({@link #changesTo}). Так журнал аудита видит старые значения: обработчик событий жизненного
 * цикла сущности их уже не знает.
 */
public record RequestSnapshot(RequestPriority priority, UUID typeId, Instant desiredDueAt,
        String description, UUID assigneeId) {

    public static RequestSnapshot of(Request request) {
        return new RequestSnapshot(request.getPriority(), request.getTypeId(),
                request.getDesiredDueAt(), request.getDescription(), request.getAssigneeId());
    }

    /** Изменения от этого снимка к {@code after} — по одному на каждое изменённое поле. */
    public List<FieldChange> changesTo(RequestSnapshot after) {
        List<FieldChange> changes = new ArrayList<>();
        addIfChanged(changes, AuditedField.PRIORITY, priority, after.priority);
        addIfChanged(changes, AuditedField.TYPE, typeId, after.typeId);
        addIfChanged(changes, AuditedField.DESIRED_DUE_AT, desiredDueAt, after.desiredDueAt);
        addIfChanged(changes, AuditedField.DESCRIPTION, description, after.description);
        addIfChanged(changes, AuditedField.ASSIGNEE, assigneeId, after.assigneeId);
        return changes;
    }

    private static void addIfChanged(List<FieldChange> changes, AuditedField field,
            Object before, Object after) {
        if (!Objects.equals(before, after)) {
            changes.add(new FieldChange(field, asText(before), asText(after)));
        }
    }

    /** В журнале значение — строка: имя варианта перечисления, идентификатор, момент в ISO-8601. */
    private static String asText(Object value) {
        if (value == null) {
            return null;
        }
        return value instanceof Enum<?> variant ? variant.name() : value.toString();
    }
}
