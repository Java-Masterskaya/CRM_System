package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RequestTransitionsTest {

    static Stream<Arguments> allowedTransitions() {
        return Stream.of(
                Arguments.of(RequestStatus.NEW, RequestStatus.CONTACTED),
                Arguments.of(RequestStatus.NEW, RequestStatus.CANCELLED),

                Arguments.of(RequestStatus.CONTACTED, RequestStatus.IN_PROGRESS),
                Arguments.of(RequestStatus.CONTACTED, RequestStatus.REJECTED),
                Arguments.of(RequestStatus.CONTACTED, RequestStatus.CANCELLED),

                Arguments.of(RequestStatus.IN_PROGRESS, RequestStatus.ON_HOLD),
                Arguments.of(RequestStatus.IN_PROGRESS, RequestStatus.DONE),
                Arguments.of(RequestStatus.IN_PROGRESS, RequestStatus.REJECTED),
                Arguments.of(RequestStatus.IN_PROGRESS, RequestStatus.CANCELLED),

                Arguments.of(RequestStatus.ON_HOLD, RequestStatus.IN_PROGRESS)
        );
    }

    @ParameterizedTest
    @MethodSource("allowedTransitions")
    void givenAllowedPair_whenCheck_thenReturnsTrue(
            RequestStatus from,
            RequestStatus to) {

        assertThat(RequestTransitions.isAllowed(from, to)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("allStatusPairs")
    void givenStatusPair_whenCheck_thenMatchesMatrix(
            RequestStatus from,
            RequestStatus to) {

        boolean expected = RequestTransitions.allowedFrom(from).contains(to);

        assertThat(RequestTransitions.isAllowed(from, to))
                .as("Переход %s → %s", from, to)
                .isEqualTo(expected);
    }

    static Stream<Arguments> allStatusPairs() {
        return Stream.of(RequestStatus.values())
                .flatMap(from -> Stream.of(RequestStatus.values())
                        .map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest
    @MethodSource("terminalStatuses")
    void givenTerminalStatus_whenCheckTransitions_thenAllForbidden(
            RequestStatus status) {

        assertThat(RequestTransitions.allowedFrom(status))
                .as("Терминальный статус %s не должен иметь переходов", status)
                .isEmpty();
    }

    static Stream<RequestStatus> terminalStatuses() {
        return Stream.of(
                RequestStatus.DONE,
                RequestStatus.REJECTED,
                RequestStatus.CANCELLED);
    }

    @ParameterizedTest
    @MethodSource("nonRejectedStatuses")
    void givenNonRejectedStatus_whenCheckRequiresReason_thenFalse(
            RequestStatus status) {

        assertThat(RequestTransitions.requiresReason(status)).isFalse();
    }

    static Stream<RequestStatus> nonRejectedStatuses() {
        return Stream.of(RequestStatus.values())
                .filter(status -> status != RequestStatus.REJECTED);
    }

    @Test
    void givenRejectedStatus_whenCheckRequiresReason_thenTrue() {
        assertThat(RequestTransitions.requiresReason(RequestStatus.REJECTED))
                .isTrue();
    }

    @Test
    void givenNullStatus_whenCheckTransition_thenForbidden() {
        assertThat(RequestTransitions.isAllowed(null, RequestStatus.NEW))
                .isFalse();
        assertThat(RequestTransitions.isAllowed(RequestStatus.NEW, null))
                .isFalse();
        assertThat(RequestTransitions.isAllowed(null, null))
                .isFalse();
        assertThat(RequestTransitions.allowedFrom(null))
                .isEmpty();
    }
}
