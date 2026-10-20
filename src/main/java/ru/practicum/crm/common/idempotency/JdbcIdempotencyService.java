package ru.practicum.crm.common.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;

@Service
@RequiredArgsConstructor
public class JdbcIdempotencyService implements IdempotencyService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional
    public <T> IdempotencyResult<T> execute(
            UUID tenantId,
            UUID userId,
            String operation,
            String idempotencyKey,
            Object request,
            int successStatus,
            Class<T> responseType,
            Supplier<T> action
    ) {
        String requestHash = hashRequest(request);

        int inserted = jdbcTemplate.update(
                """
                INSERT INTO idempotency_records (
                    id,
                    tenant_id,
                    user_id,
                    operation,
                    idempotency_key,
                    request_hash,
                    created_at,
                    updated_at
                )
                VALUES (?, ?, ?, ?, ?, ?, NOW(), NOW())
                ON CONFLICT (
                    tenant_id,
                    user_id,
                    operation,
                    idempotency_key
                ) DO NOTHING
                """,
                UUID.randomUUID(),
                tenantId,
                userId,
                operation,
                idempotencyKey,
                requestHash
        );

        if (inserted == 0) {
            StoredResponse stored = findStoredResponse(
                    tenantId,
                    userId,
                    operation,
                    idempotencyKey
            );

            if (!MessageDigest.isEqual(
                    stored.requestHash().getBytes(StandardCharsets.UTF_8),
                    requestHash.getBytes(StandardCharsets.UTF_8))) {
                throw new ApiException(
                        ErrorCode.IDEMPOTENCY_KEY_REUSED,
                        ErrorCode.IDEMPOTENCY_KEY_REUSED.getDefaultDetail()
                );
            }

            if (stored.status() == null || stored.body() == null) {
                throw new IllegalStateException(
                        "Запись идемпотентности не содержит завершённого ответа"
                );
            }

            return new IdempotencyResult<>(
                    stored.status(),
                    readBody(stored.body(), responseType)
            );
        }

        T response = action.get();
        String responseBody = writeBody(response);

        int updated = jdbcTemplate.update(
                """
                UPDATE idempotency_records
                SET response_status = ?,
                    response_body = ?,
                    updated_at = NOW()
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND operation = ?
                  AND idempotency_key = ?
                  AND request_hash = ?
                  AND response_status IS NULL
                  AND response_body IS NULL
                """,
                successStatus,
                responseBody,
                tenantId,
                userId,
                operation,
                idempotencyKey,
                requestHash
        );

        if (updated != 1) {
            throw new IllegalStateException(
                    "Не удалось сохранить результат идемпотентности"
            );
        }

        return new IdempotencyResult<>(successStatus, response);
    }

    private StoredResponse findStoredResponse(
            UUID tenantId,
            UUID userId,
            String operation,
            String idempotencyKey
    ) {
        List<StoredResponse> results = jdbcTemplate.query(
                """
                SELECT request_hash, response_status, response_body
                FROM idempotency_records
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND operation = ?
                  AND idempotency_key = ?
                FOR UPDATE
                """,
                (resultSet, rowNum) -> {
                    Object statusValue = resultSet.getObject("response_status");

                    Integer status = statusValue == null
                            ? null
                            : ((Number) statusValue).intValue();

                    return new StoredResponse(
                            resultSet.getString("request_hash"),
                            status,
                            resultSet.getString("response_body")
                    );
                },
                tenantId,
                userId,
                operation,
                idempotencyKey
        );

        if (results.isEmpty()) {
            throw new IllegalStateException(
                    "Запись идемпотентности не найдена после конфликта ключа"
            );
        }

        return results.get(0);
    }

    private String hashRequest(Object request) {
        try {
            JsonNode requestTree = objectMapper.valueToTree(request);
            JsonNode canonicalTree = canonicalize(requestTree);

            return sha256(objectMapper.writeValueAsString(canonicalTree));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось вычислить отпечаток запроса",
                    exception
            );
        }
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            ObjectNode sorted = objectMapper.createObjectNode();
            List<String> names = new ArrayList<>();

            node.fieldNames().forEachRemaining(names::add);
            names.sort(Comparator.naturalOrder());

            for (String name : names) {
                sorted.set(name, canonicalize(node.get(name)));
            }

            return sorted;
        }

        if (node.isArray()) {
            ArrayNode sorted = objectMapper.createArrayNode();

            for (JsonNode element : node) {
                sorted.add(canonicalize(element));
            }

            return sorted;
        }

        return node.deepCopy();
    }

    private String sha256(String value) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    value.getBytes(StandardCharsets.UTF_8)
            );

            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(
                    "SHA-256 algorithm is not available",
                    exception
            );
        }
    }

    private String writeBody(Object body) {
        try {
            return objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось сохранить тело ответа",
                    exception
            );
        }
    }

    private <T> T readBody(String body, Class<T> responseType) {
        try {
            return objectMapper.readValue(body, responseType);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(
                    "Не удалось восстановить сохранённый ответ",
                    exception
            );
        }
    }

    private record StoredResponse(
            String requestHash,
            Integer status,
            String body
    ) {
    }
}
