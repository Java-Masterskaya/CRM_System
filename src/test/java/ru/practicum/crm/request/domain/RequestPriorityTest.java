package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.Comparator;
import org.junit.jupiter.api.Test;

class RequestPriorityTest {

    @Test
    void rank_whenPrioritiesOrderedByIt_goesFromLeastToMostUrgent() {
        assertThat(Arrays.stream(RequestPriority.values())
                .sorted(Comparator.comparingInt(RequestPriority::getRank)))
                .containsExactly(RequestPriority.LOW, RequestPriority.NORMAL, RequestPriority.HIGH,
                        RequestPriority.URGENT);
    }

    @Test
    void rank_whenCompared_isUniqueForEveryPriority() {
        assertThat(Arrays.stream(RequestPriority.values()).map(RequestPriority::getRank))
                .doesNotHaveDuplicates();
    }

    @Test
    void resolve_whenClientGavePriority_keepsItOverTypeDefault() {
        assertThat(RequestPriority.resolve(RequestPriority.LOW, RequestPriority.URGENT))
                .isEqualTo(RequestPriority.LOW);
    }

    @Test
    void resolve_whenClientGaveNothing_takesTypeDefault() {
        assertThat(RequestPriority.resolve(null, RequestPriority.URGENT))
                .isEqualTo(RequestPriority.URGENT);
    }

    @Test
    void resolve_whenNeitherClientNorTypeGavePriority_fallsBackToNormal() {
        assertThat(RequestPriority.resolve(null, null)).isEqualTo(RequestPriority.NORMAL);
        assertThat(RequestPriority.FALLBACK).isEqualTo(RequestPriority.NORMAL);
    }
}
