package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
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
import ru.practicum.crm.request.repository.RequestSpecifications;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Очередь работ оператора (T-052, ТЗ §6.3): его незавершённые заявки — что делать сейчас.
 *
 * <p>Порядок фиксированный: сначала более приоритетные, при равном приоритете — созданные раньше.
 * Сортировка по срокам и просрочке появится с T-090. Исполнитель — текущий пользователь,
 * арендатор — из контекста запроса, поэтому заявки других операторов и других арендаторов в
 * очередь не попадают ни при каких параметрах.
 *
 * <p>Право {@code REQUEST_READ_ALL} проверяет {@code @PreAuthorize}: оно есть у оператора и
 * администратора, у клиента нет — он получает 403.
 */
@Service
public class RequestQueueService {

    /**
     * Порядок очереди; {@code id} — последним, чтобы заявки с одинаковым временем шли на всех
     * страницах в одном порядке. Совпадает с индексом {@code idx_requests_tenant_assignee_queue}.
     */
    static final Sort QUEUE_ORDER = Sort.by(Sort.Order.desc("priorityRank"),
            Sort.Order.asc("createdAt"), Sort.Order.asc(PageRequests.TIE_BREAKER_FIELD));

    private final RequestRepository requests;
    private final CurrentUser currentUser;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public RequestQueueService(RequestRepository requests, CurrentUser currentUser,
            TenantContext tenantContext) {
        this.requests = requests;
        this.currentUser = currentUser;
        this.tenantContext = tenantContext;
    }

    /**
     * Страница очереди текущего пользователя.
     *
     * @param statuses только эти статусы; пусто — все незавершённые
     * @param pageable номер и размер страницы; порядок задаёт очередь
     * @throws ApiException {@code VALIDATION_FAILED} с параметром {@code status}, если среди
     *     статусов есть завершённый: таких заявок в очереди не бывает, и пустой список скрыл бы
     *     ошибку в запросе
     */
    @PreAuthorize("hasAuthority('REQUEST_READ_ALL')")
    @Transactional(readOnly = true)
    public PageResponse<RequestRegistryItem> queue(Set<RequestStatus> statuses,
            Pageable pageable) {
        List<RequestStatus> finished = statuses.stream()
                .filter(RequestStatus::isTerminal)
                .sorted()
                .toList();
        if (!finished.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED,
                    "В очереди только незавершённые заявки.",
                    List.of(ValidationError.ofParameter("status",
                            "завершённые статусы не допускаются: " + finished)));
        }
        Pageable bounded = PageRequests.requireBounded(pageable);
        Page<Request> page = requests.findAll(specificationOf(statuses),
                PageRequest.of(bounded.getPageNumber(), bounded.getPageSize(), QUEUE_ORDER));
        return PageResponse.of(page.map(RequestListItems::of));
    }

    private Specification<Request> specificationOf(Set<RequestStatus> statuses) {
        return RequestSpecifications.ofTenant(tenantContext.getCurrentTenantId())
                .and(RequestSpecifications.assignedTo(currentUser.id()))
                .and(statuses.isEmpty()
                        ? RequestSpecifications.notFinished()
                        : RequestSpecifications.inStatuses(statuses));
    }
}
