package ru.practicum.crm.common.pagination;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;

public final class PageRequests {

    public static final int DEFAULT_PAGE = 0;
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;
    public static final String TIE_BREAKER_FIELD = "id";

    private static final String PAGE_PARAM = "page";
    private static final String SIZE_PARAM = "size";
    private static final String SORT_PARAM = "sort";
    private static final String DIRECTION_DESC = "desc";
    private static final String DIRECTION_ASC = "asc";

    private PageRequests() {
    }

    public static Pageable toPageable(Integer page, Integer size, List<String> sort,
            Set<String> allowedSortFields) {
        List<ValidationError> errors = new ArrayList<>();

        int resolvedPage = page == null ? DEFAULT_PAGE : page;
        if (resolvedPage < 0) {
            errors.add(ValidationError.ofParameter(PAGE_PARAM, "не может быть отрицательным"));
        }

        int resolvedSize = size == null ? DEFAULT_SIZE : size;
        if (resolvedSize < 1) {
            errors.add(ValidationError.ofParameter(SIZE_PARAM, "должно быть не меньше 1"));
        } else if (resolvedSize > MAX_SIZE) {
            errors.add(ValidationError.ofParameter(SIZE_PARAM,
                    "должно быть не больше " + MAX_SIZE));
        }

        Sort resolvedSort = withTieBreaker(parseSort(sort, allowedSortFields, errors));

        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
        return PageRequest.of(resolvedPage, resolvedSize, resolvedSort);
    }

    private static Sort withTieBreaker(Sort sort) {
        if (sort.getOrderFor(TIE_BREAKER_FIELD) != null) {
            return sort;
        }
        return sort.and(Sort.by(Sort.Order.asc(TIE_BREAKER_FIELD)));
    }

    private static Sort parseSort(List<String> sort, Set<String> allowedSortFields,
            List<ValidationError> errors) {
        if (sort == null || sort.isEmpty()) {
            return Sort.unsorted();
        }
        List<Sort.Order> orders = new ArrayList<>();
        for (String expression : sort) {
            Sort.Order order = parseOrder(expression, allowedSortFields, errors);
            if (order != null) {
                orders.add(order);
            }
        }
        return orders.isEmpty() ? Sort.unsorted() : Sort.by(orders);
    }

    private static Sort.Order parseOrder(String expression, Set<String> allowedSortFields,
            List<ValidationError> errors) {
        if (expression == null || expression.isBlank()) {
            errors.add(ValidationError.ofParameter(SORT_PARAM, "не должно быть пустым"));
            return null;
        }
        String[] parts = expression.split(",", 2);
        String field = parts[0].trim();
        if (!allowedSortFields.contains(field)) {
            errors.add(ValidationError.ofParameter(SORT_PARAM,
                    "сортировка по полю «" + field + "» не поддерживается"));
            return null;
        }
        if (parts.length == 1) {
            return Sort.Order.asc(field);
        }
        String direction = parts[1].trim().toLowerCase(Locale.ROOT);
        if (DIRECTION_ASC.equals(direction)) {
            return Sort.Order.asc(field);
        }
        if (DIRECTION_DESC.equals(direction)) {
            return Sort.Order.desc(field);
        }
        errors.add(ValidationError.ofParameter(SORT_PARAM,
                "направление сортировки «" + parts[1].trim() + "» не распознано, "
                        + "допустимы asc и desc"));
        return null;
    }
}
