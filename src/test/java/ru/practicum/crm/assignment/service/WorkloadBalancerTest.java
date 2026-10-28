package ru.practicum.crm.assignment.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import ru.practicum.crm.user.api.dto.UserContactDto;

/**
 * Выбор исполнителя по нагрузке без базы: сравниваются только числа, которые приходят из
 * одного агрегирующего запроса (T-051).
 */
class WorkloadBalancerTest {

    private static final UserContactDto OLGA =
            new UserContactDto(UUID.randomUUID(), "olga@example.com", "Ольга");
    private static final UserContactDto IVAN =
            new UserContactDto(UUID.randomUUID(), "ivan@example.com", "Иван");
    private static final UserContactDto PETR =
            new UserContactDto(UUID.randomUUID(), "petr@example.com", "Пётр");

    @Test
    void leastLoaded_whenNoCandidates_returnsEmpty() {
        assertThat(WorkloadBalancer.leastLoaded(List.of(), Map.of())).isEmpty();
    }

    @Test
    void leastLoaded_whenLoadsDiffer_picksCandidateWithFewestUnfinishedRequests() {
        Map<UUID, Long> workload = Map.of(OLGA.id(), 5L, IVAN.id(), 1L, PETR.id(), 4L);

        assertThat(WorkloadBalancer.leastLoaded(List.of(OLGA, IVAN, PETR), workload))
                .contains(IVAN);
    }

    @Test
    void leastLoaded_whenCandidateAbsentFromWorkload_treatsTheirLoadAsZero() {
        Map<UUID, Long> workload = Map.of(OLGA.id(), 2L, PETR.id(), 3L);

        assertThat(WorkloadBalancer.leastLoaded(List.of(OLGA, IVAN, PETR), workload))
                .contains(IVAN);
    }

    @Test
    void leastLoaded_whenLoadsEqual_keepsCandidateOrderSoChoiceIsRepeatable() {
        assertThat(WorkloadBalancer.leastLoaded(List.of(OLGA, IVAN, PETR), Map.of()))
                .contains(OLGA);
        assertThat(WorkloadBalancer.leastLoaded(List.of(IVAN, PETR, OLGA), Map.of()))
                .contains(IVAN);
    }

    @Test
    void leastLoaded_whenEveryCandidateIsLoaded_picksTheSmallestNumberOfRequests() {
        Map<UUID, Long> workload = Map.of(OLGA.id(), 7L, IVAN.id(), 3L, PETR.id(), 6L);

        assertThat(WorkloadBalancer.leastLoaded(List.of(OLGA, IVAN, PETR), workload))
                .contains(IVAN);
    }
}
