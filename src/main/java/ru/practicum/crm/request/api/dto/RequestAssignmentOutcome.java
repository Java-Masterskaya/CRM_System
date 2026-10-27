package ru.practicum.crm.request.api.dto;

/**
 * Результат назначения: состояние заявки после операции и признак, что исполнитель изменился.
 *
 * <p>{@code changed = false} — назначение было холостым: исполнитель остался прежним, поэтому
 * автору не нужно отправлять письмо. Так же повторный {@code take} тем же оператором не меняет
 * заявку и не уведомляет автора.
 */
public record RequestAssignmentOutcome(
        RequestAssignmentDto assignment,
        boolean changed
) {
}
