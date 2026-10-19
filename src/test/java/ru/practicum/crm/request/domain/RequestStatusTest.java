package ru.practicum.crm.request.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RequestStatusTest {

    /** Новый статус нельзя добавить без названия: иначе письмо клиенту выйдет с пустым местом. */
    @ParameterizedTest
    @EnumSource(RequestStatus.class)
    void title_isSetForEveryStatus(RequestStatus status) {
        assertThat(status.title()).isNotBlank();
    }

    /** По названию в письме клиент различает статусы — повторяться они не должны. */
    @Test
    void titles_areDistinct() {
        assertThat(Arrays.stream(RequestStatus.values()).map(RequestStatus::title))
                .doesNotHaveDuplicates();
    }
}
