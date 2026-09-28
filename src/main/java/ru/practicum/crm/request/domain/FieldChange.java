package ru.practicum.crm.request.domain;

/**
 * Изменение одного значимого поля заявки: было и стало. Значения — строки, как они хранятся в
 * журнале; {@code null} означает пустое поле.
 */
public record FieldChange(AuditedField field, String oldValue, String newValue) {
}
