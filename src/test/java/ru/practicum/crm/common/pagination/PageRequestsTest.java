package ru.practicum.crm.common.pagination;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;

class PageRequestsTest {

    private static final Set<String> ALLOWED = Set.of("createdAt", "status");

    @Test
    void toPageable_whenNoParameters_usesDefaultsAndOrdersById() {
        Pageable pageable = PageRequests.toPageable(null, null, null, ALLOWED);

        assertThat(pageable.getPageNumber()).isEqualTo(PageRequests.DEFAULT_PAGE);
        assertThat(pageable.getPageSize()).isEqualTo(PageRequests.DEFAULT_SIZE);
        assertThat(pageable.getSort().toList()).containsExactly(Sort.Order.asc("id"));
    }

    @Test
    void toPageable_whenSizeAboveLimit_rejectsWithValidationError() {
        ApiException thrown = catchThrowableOfType(
                () -> PageRequests.toPageable(0, 500, null, ALLOWED), ApiException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
        assertThat(thrown.getErrors())
                .containsExactly(ValidationError.ofParameter("size", "должно быть не больше 100"));
    }

    @Test
    void toPageable_whenPageIsNegative_rejectsWithValidationError() {
        ApiException thrown = catchThrowableOfType(
                () -> PageRequests.toPageable(-1, 20, null, ALLOWED), ApiException.class);

        assertThat(thrown.getErrors()).containsExactly(
                ValidationError.ofParameter("page", "не может быть отрицательным"));
    }

    @Test
    void toPageable_whenSizeIsZero_rejectsWithValidationError() {
        ApiException thrown = catchThrowableOfType(
                () -> PageRequests.toPageable(0, 0, null, ALLOWED), ApiException.class);

        assertThat(thrown.getErrors())
                .containsExactly(ValidationError.ofParameter("size", "должно быть не меньше 1"));
    }

    @Test
    void toPageable_whenSortFieldNotAllowed_rejectsWithValidationError() {
        ApiException thrown = catchThrowableOfType(
                () -> PageRequests.toPageable(0, 20, List.of("tenantId"), ALLOWED),
                ApiException.class);

        assertThat(thrown.getErrors()).hasSize(1);
        assertThat(thrown.getErrors().get(0).parameter()).isEqualTo("sort");
        assertThat(thrown.getErrors().get(0).detail())
                .isEqualTo("сортировка по полю «tenantId» не поддерживается");
    }

    @Test
    void toPageable_whenDirectionIsUnknown_rejectsWithValidationError() {
        ApiException thrown = catchThrowableOfType(
                () -> PageRequests.toPageable(0, 20, List.of("status,up"), ALLOWED),
                ApiException.class);

        assertThat(thrown.getErrors().get(0).detail())
                .isEqualTo("направление сортировки «up» не распознано, допустимы asc и desc");
    }

    @Test
    void toPageable_whenSortIsValid_appendsIdAsLastKey() {
        Pageable pageable = PageRequests.toPageable(1, 50,
                List.of("status", "createdAt,desc"), ALLOWED);

        assertThat(pageable.getPageNumber()).isEqualTo(1);
        assertThat(pageable.getPageSize()).isEqualTo(50);
        assertThat(pageable.getSort().toList()).containsExactly(
                Sort.Order.asc("status"), Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    @Test
    void toPageable_whenClientSortsById_doesNotDuplicateTieBreaker() {
        Pageable pageable = PageRequests.toPageable(0, 20, List.of("id,desc"),
                Set.of("id", "status"));

        assertThat(pageable.getSort().toList()).containsExactly(Sort.Order.desc("id"));
    }

    @Test
    void toPageable_whenSeveralParametersInvalid_collectsAllErrors() {
        ApiException thrown = catchThrowableOfType(
                () -> PageRequests.toPageable(-1, 500, List.of("secret"), ALLOWED),
                ApiException.class);

        assertThat(thrown.getErrors()).hasSize(3);
        assertThat(thrown.getErrors()).extracting(ValidationError::parameter)
                .containsExactly("page", "size", "sort");
    }

    @Test
    void toPageable_whenSortIsBlank_rejectsWithValidationError() {
        assertThatThrownBy(() -> PageRequests.toPageable(0, 20, List.of(" "), ALLOWED))
                .isInstanceOf(ApiException.class);
    }
}
