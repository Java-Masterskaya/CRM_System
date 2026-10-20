package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

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
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.common.security.CurrentUser;
import ru.practicum.crm.request.api.dto.RequestRegistryItem;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Очередь без базы: порядок, проверка статусов и строки очереди. Какие заявки попадают в очередь,
 * арендатор, исполнитель и план запроса проверяет {@code RequestQueueIntegrationTest}.
 */
class RequestQueueServiceTest {

    private static final UUID TENANT_ID = UUID.randomUUID();
    private static final UUID OPERATOR_ID = UUID.randomUUID();

    private final RequestRepository requests = mock(RequestRepository.class);
    private final CurrentUser currentUser = mock(CurrentUser.class);
    private final TenantContext tenantContext = mock(TenantContext.class);
    private final RequestQueueService service =
            new RequestQueueService(requests, currentUser, tenantContext);

    @BeforeEach
    void setUp() {
        when(tenantContext.getCurrentTenantId()).thenReturn(TENANT_ID);
        when(currentUser.id()).thenReturn(OPERATOR_ID);
        when(requests.findAll(any(), any(Pageable.class))).thenReturn(Page.empty());
    }

    /**
     * DoD: сначала более приоритетные, при равном приоритете — созданные раньше; {@code id} —
     * последним ключом. Номер и размер страницы — из запроса.
     */
    @Test
    void queue_ordersByPriorityThenOldestFirstOnRequestedPage() {
        service.queue(Set.of(), PageRequest.of(2, 30));

        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        verify(requests).findAll(any(), pageable.capture());
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(30);
        assertThat(pageable.getValue().getSort()).containsExactly(
                Sort.Order.desc("priorityRank"),
                Sort.Order.asc("createdAt"),
                Sort.Order.asc("id"));
    }

    /**
     * Завершённых заявок в очереди не бывает: просьба показать их — ошибка в запросе, а не
     * пустой список.
     */
    @Test
    void queue_withFinishedStatus_isValidationErrorOnStatus() {
        assertThatThrownBy(() -> service.queue(Set.of(RequestStatus.NEW, RequestStatus.DONE,
                RequestStatus.CANCELLED), PageRequest.of(0, 20)))
                .isInstanceOfSatisfying(ApiException.class, ex -> {
                    assertThat(ex.getErrorCode()).isEqualTo(ErrorCode.VALIDATION_FAILED);
                    assertThat(ex.getErrors()).extracting(ValidationError::parameter)
                            .containsExactly("status");
                    assertThat(ex.getErrors().get(0).detail()).contains("DONE", "CANCELLED")
                            .doesNotContain("NEW");
                });

        verifyNoInteractions(requests);
    }

    /** Страница больше допустимой не уходит в базу, даже если её передали в обход API. */
    @Test
    void queue_whenPageIsTooLarge_isRejectedBeforeQuery() {
        assertThatThrownBy(() -> service.queue(Set.of(),
                PageRequest.of(0, PageRequests.MAX_SIZE + 1)))
                .isInstanceOf(ApiException.class);

        verifyNoInteractions(requests);
    }

    /** Строка очереди — та же, что в реестре; страница несёт общее количество. */
    @Test
    void queue_mapsRequestsToItemsWithPageInfo() {
        Request request = new Request(TENANT_ID, UUID.randomUUID(), "Выгрузка за квартал",
                "Нужен отчёт", RequestStatus.IN_PROGRESS);
        ReflectionTestUtils.setField(request, "id", UUID.randomUUID());
        when(requests.findAll(any(), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(request), PageRequest.of(0, 1), 3));

        PageResponse<RequestRegistryItem> page = service.queue(Set.of(), PageRequest.of(0, 1));

        assertThat(page.totalElements()).isEqualTo(3);
        assertThat(page.totalPages()).isEqualTo(3);
        assertThat(page.content()).extracting(RequestRegistryItem::id)
                .containsExactly(request.getId());
        assertThat(page.content().get(0).status()).isEqualTo(RequestStatus.IN_PROGRESS);
    }
}
