package ru.practicum.crm.common.idempotency;

public record IdempotencyResult<T>(
        int status,
        T body
) {
}
