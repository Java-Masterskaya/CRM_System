package ru.practicum.crm.common.error;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ErrorCodeTest {

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 405, 406, 415, 500, 503})
    void byStatus_whenStatusProducedBySpring_returnsCodeWithSameStatus(int status) {
        ErrorCode code = ErrorCode.byStatus(status);

        assertThat(code).isNotNull();
        assertThat(code.getStatus().value()).isEqualTo(status);
    }

    @ParameterizedTest
    @ValueSource(ints = {402, 409, 418, 422, 502, 504})
    void byStatus_whenStatusNotInCatalogue_neverReturnsNull(int status) {
        assertThat(ErrorCode.byStatus(status)).isNotNull();
    }

    @Test
    void byStatus_whenUnknownServerError_returnsInternalError() {
        assertThat(ErrorCode.byStatus(502)).isEqualTo(ErrorCode.INTERNAL_ERROR);
    }

    @Test
    void byStatus_whenUnknownClientError_returnsMalformedRequest() {
        assertThat(ErrorCode.byStatus(418)).isEqualTo(ErrorCode.MALFORMED_REQUEST);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 409})
    void byStatus_whenDomainCodeSharesStatus_neverReturnsDomainCode(int status) {
        assertThat(ErrorCode.byStatus(status)).isNotIn(ErrorCode.VALIDATION_FAILED,
                ErrorCode.INVALID_CREDENTIALS, ErrorCode.INVALID_TRANSITION,
                ErrorCode.STALE_VERSION, ErrorCode.ALREADY_EXISTS);
    }

    @Test
    void type_whenCodeAdded_isBuiltFromName() {
        assertThat(ErrorCode.SERVICE_UNAVAILABLE.getType())
                .hasToString("https://crm.example/problems/service-unavailable");
        assertThat(ErrorCode.NOT_ACCEPTABLE.getType())
                .hasToString("https://crm.example/problems/not-acceptable");
    }
}
