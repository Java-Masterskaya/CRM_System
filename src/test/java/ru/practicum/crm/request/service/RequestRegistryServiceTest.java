package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.request.api.dto.RequestRegistryFilter;
import ru.practicum.crm.request.api.dto.RequestRegistryItem;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Реестр без базы: проверка периода, перевод полей сортировки и строки реестра. Сами фильтры,
 * арендатор и планы запросов проверяет {@code RequestRegistryIntegrationTest}.
 */
class RequestRegistryServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final RequestRegistryFilter NO_FILTERS =
            new RequestRegistryFilter(null, null, null, null, null, false);

    private final RequestRepository requests = mock(RequestRepository.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final RequestRegistryService service =
            new RequestRegistryService(requests, tenantContext);

    @BeforeEach
    void setUp() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(requests.findAll(any(), any(Pageable.class))).thenReturn(Page.empty());
    }

    /** Пустой или перевёрнутый период — ошибка с именем параметра, а не пустой список. */
    @Test
    void list_whenPeriodEndsNotLaterThanItStarts_isValidationErrorOnCreatedTo() {
        Instant moment = Instant.parse("2026-10-01T00:00:00Z");

        for (Instant end : List.of(moment, moment.minusSeconds(1))) {
            RequestRegistryFilter filter =
                    new RequestRegistryFilter(null, null, null, moment, end, false);

            assertThatThrownBy(() -> service.list(filter, PageRequest.of(0, 20)))
                    .isInstanceOfSatisfying(ApiException.class, ex -> {
                        assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                        assertThat(ex.getErrors()).extracting(ValidationError::parameter)
                                .containsExactly("createdTo");
                    });
        }
        verifyNoInteractions(requests);
    }

    /**
     * Сортировка из запроса — по именам API; база сортирует по полям заявки. Приоритет — по рангу,
     * остальные поля и добавочный порядок по {@code id} не меняются.
     */
    @Test
    void list_translatesSortFieldsToRequestFields() {
        Pageable fromApi = PageRequests.toPageable(1, 30, List.of("priority,desc",
                "createdAt,asc"), RequestRegistryService.SORT_FIELDS.keySet());

        service.list(NO_FILTERS, fromApi);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(requests).findAll(any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(30);
        assertThat(pageable.getValue().getSort()).containsExactly(
                Sort.Order.desc("priorityRank"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
    }

    /** Сортировка по умолчанию — сначала новые. */
    @Test
    void list_byDefault_sortsNewestFirst() {
        Pageable fromApi = PageRequests.toPageable(null, null,
                List.of(RequestRegistryService.DEFAULT_SORT),
                RequestRegistryService.SORT_FIELDS.keySet());

        service.list(NO_FILTERS, fromApi);

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(requests).findAll(any(), pageable.capture());
        assertThat(pageable.getValue().getSort())
                .containsExactly(Sort.Order.desc("createdAt"), Sort.Order.asc("id"));
    }

    /**
     * Строка реестра — поля заявки и признак «просрочена сейчас»: у закрытой заявки признак
     * просрочки остаётся, но в реестре она не просрочена.
     */
    @Test
    void list_mapsRequestsToRegistryItemsWithPageInfo() {
        Request open = request(RequestStatus.IN_PROGRESS, RequestPriority.URGENT);
        open.setResolutionOverdue(true);
        Request closed = request(RequestStatus.DONE, RequestPriority.LOW);
        closed.setResolutionOverdue(true);
        when(requests.findAll(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(open, closed), PageRequest.of(0, 2), 5));

        PageResponse<RequestRegistryItem> page = service.list(NO_FILTERS, PageRequest.of(0, 2));

        assertThat(page.totalElements()).isEqualTo(5);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).extracting(RequestRegistryItem::id)
                .containsExactly(open.getId(), closed.getId());
        RequestRegistryItem first = page.content().get(0);
        assertThat(first.subject()).isEqualTo("Выгрузка за квартал");
        assertThat(first.status()).isEqualTo(RequestStatus.IN_PROGRESS);
        assertThat(first.priority()).isEqualTo(RequestPriority.URGENT);
        assertThat(first.typeId()).isEqualTo(open.getTypeId());
        assertThat(first.authorId()).isEqualTo(open.getAuthorId());
        assertThat(first.version()).isEqualTo(7);
        assertThat(first.overdue()).isTrue();
        assertThat(page.content().get(1).overdue()).isFalse();
    }

    /** Страница больше допустимой не уходит в базу, даже если её передали в обход API. */
    @Test
    void list_whenPageIsTooLarge_isRejectedBeforeQuery() {
        assertThatThrownBy(() -> service.list(new RequestRegistryFilter(Set.of(), null, null,
                null, null, false), PageRequest.of(0, PageRequests.MAX_SIZE + 1)))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(requests);
    }

    private static Request request(RequestStatus status, RequestPriority priority) {
        Request request = new Request(TENANT_ID, UUID.randomUUID(), "Выгрузка за квартал",
                "Нужен отчёт", status);
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        ReflectionTestUtils.setField(request, "version", 7L);
        request.setTypeId(UUID.randomUUID());
        request.setPriority(priority);
        return request;
    }
}
