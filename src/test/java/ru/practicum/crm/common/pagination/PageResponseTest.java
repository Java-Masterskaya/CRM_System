package ru.practicum.crm.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class PageResponseTest {

    @Test
    void of_whenTotalIsNotMultipleOfSize_roundsPagesUp() {
        PageResponse<String> response = PageResponse.of(List.of("a", "b"), 0, 20, 137);

        assertThat(response.totalPages()).isEqualTo(7);
        assertThat(response.totalElements()).isEqualTo(137);
        assertThat(response.page()).isZero();
        assertThat(response.size()).isEqualTo(20);
        assertThat(response.nextCursor()).isNull();
    }

    @Test
    void of_whenThereAreNoElements_hasNoPages() {
        PageResponse<String> response = PageResponse.of(List.of(), 0, 20, 0);

        assertThat(response.totalPages()).isZero();
        assertThat(response.content()).isEmpty();
    }

    @Test
    void of_whenBuiltFromSpringPage_copiesMetadata() {
        PageResponse<String> response = PageResponse.of(new PageImpl<>(
                List.of("u", "v", "w", "x", "y"), PageRequest.of(2, 10), 25));

        assertThat(response.page()).isEqualTo(2);
        assertThat(response.size()).isEqualTo(10);
        assertThat(response.totalElements()).isEqualTo(25);
        assertThat(response.totalPages()).isEqualTo(3);
        assertThat(response.content()).hasSize(5);
    }

    @Test
    void content_whenSourceListChanges_responseStaysUnchanged() {
        List<String> source = new java.util.ArrayList<>(List.of("a"));
        PageResponse<String> response = PageResponse.of(source, 0, 20, 1);
        source.add("b");

        assertThat(response.content()).containsExactly("a");
    }
}
