package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class RequestTransitionsTest {

    private static final Set<List<RequestStatus>> ALLOWED = Set.of(
            List.of(RequestStatus.NEW, RequestStatus.CONTACTED),
            List.of(RequestStatus.NEW, RequestStatus.REJECTED),
            List.of(RequestStatus.NEW, RequestStatus.CANCELLED),

            List.of(RequestStatus.CONTACTED, RequestStatus.IN_PROGRESS),
            List.of(RequestStatus.CONTACTED, RequestStatus.REJECTED),
            List.of(RequestStatus.CONTACTED, RequestStatus.CANCELLED),

            List.of(RequestStatus.IN_PROGRESS, RequestStatus.ON_HOLD),
            List.of(RequestStatus.IN_PROGRESS, RequestStatus.DONE),
            List.of(RequestStatus.IN_PROGRESS, RequestStatus.REJECTED),
            List.of(RequestStatus.IN_PROGRESS, RequestStatus.CANCELLED),

            List.of(RequestStatus.ON_HOLD, RequestStatus.IN_PROGRESS)
    );

    @ParameterizedTest
    @MethodSource("allStatusPairs")
    void givenStatusPair_whenCheck_thenMatchesSpecification(
            RequestStatus from,
            RequestStatus to) {

        assertThat(RequestTransitions.isAllowed(from, to))
                .as("Переход %s → %s", from, to)
                .isEqualTo(ALLOWED.contains(List.of(from, to)));
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
    @MethodSource("allStatuses")
    void givenStatus_whenCheckTerminality_thenMatchesTransitionMatrix(
            RequestStatus status) {

        assertThat(RequestTransitions.allowedFrom(status).isEmpty())
                .as("Терминальность статуса %s не совпадает с матрицей", status)
                .isEqualTo(status.isTerminal());
    }

    static Stream<RequestStatus> allStatuses() {
        return Stream.of(RequestStatus.values());
    }

    @ParameterizedTest
    @MethodSource("nonRejectedStatuses")
    void givenNonRejectedStatus_whenCheckRequiresReason_thenFalse(
            RequestStatus status) {

        assertThat(RequestTransitions.requiresReason(status))
                .as("Причина не должна требоваться для %s", status)
                .isFalse();
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

    @Test
    void givenOnHold_whenCheckForbiddenTransitions_thenRejectedAndCancelledAreForbidden() {
        assertThat(RequestTransitions.isAllowed(
                RequestStatus.ON_HOLD,
                RequestStatus.REJECTED))
                .isFalse();

        assertThat(RequestTransitions.isAllowed(
                RequestStatus.ON_HOLD,
                RequestStatus.CANCELLED))
                .isFalse();
    }
}
