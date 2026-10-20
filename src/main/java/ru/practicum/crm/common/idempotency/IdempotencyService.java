package ru.practicum.crm.common.idempotency;

import java.util.UUID;
import java.util.function.Supplier;

public interface IdempotencyService {

    <T> IdempotencyResult<T> execute(
            UUID tenantId,
            UUID userId,
            String operation,
            String idempotencyKey,
            Object request,
            int successStatus,
            Class<T> responseType,
            Supplier<T> action
    );
}
