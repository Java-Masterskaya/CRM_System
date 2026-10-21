package ru.practicum.crm.request.service;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.Map;
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
import ru.practicum.crm.request.api.dto.RequestRegistryFilter;
import ru.practicum.crm.request.api.dto.RequestRegistryItem;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.repository.RequestRepository;
import ru.practicum.crm.request.repository.RequestSpecifications;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Реестр заявок административного контура (T-040, ТЗ §6.3): все заявки текущего арендатора с
 * фильтрами по статусу, типу, приоритету, периоду создания и просрочке, постранично.
 *
 * <p>Право {@code REQUEST_READ_ALL} проверяет {@code @PreAuthorize}: у оператора и
 * администратора оно есть, у клиента нет — он получает 403. Арендатор берётся из контекста
 * запроса, поэтому чужих заявок в реестре нет ни при каком сочетании фильтров.
 */
@Service
public class RequestRegistryService {

    /**
     * Поля сортировки: имя в запросе → поле заявки. Приоритет сортируется по рангу: по названию
     * порядок был бы HIGH, LOW, NORMAL, URGENT.
     */
    public static final Map<String, String> SORT_FIELDS = Map.of(
            "createdAt", "createdAt",
            "priority", "priorityRank",
            "resolutionDueAt", "resolutionDueAt");

    /** Порядок, если клиент его не задал: сначала новые заявки. */
    public static final String DEFAULT_SORT = "createdAt,desc";

    private final RequestRepository requests;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public RequestRegistryService(RequestRepository requests, TenantContext tenantContext) {
        this.requests = requests;
        this.tenantContext = tenantContext;
    }

    /**
     * Страница реестра.
     *
     * @param pageable страница и сортировка по именам из {@link #SORT_FIELDS} — их проверяет
     *     {@link PageRequests#toPageable}
     * @throws ApiException {@code VALIDATION_FAILED} с параметром {@code createdTo}, если период
     *     создания пуст или перевёрнут
     */
    @PreAuthorize("hasAuthority('REQUEST_READ_ALL')")
    @Transactional(readOnly = true)
    public PageResponse<RequestRegistryItem> list(RequestRegistryFilter filter,
            Pageable pageable) {
        if (filter.createdFrom() != null && filter.createdTo() != null
                && !filter.createdFrom().isBefore(filter.createdTo())) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, "Период создания задан неверно.",
                    List.of(ValidationError.ofParameter("createdTo",
                            "должно быть позже createdFrom")));
        }
        Page<Request> page = requests.findAll(specificationOf(filter),
                withEntityFields(PageRequests.requireBounded(pageable)));
        return PageResponse.of(page.map(RequestListItems::of));
    }

    /** Арендатор — всегда; остальные условия — только заданные. */
    private Specification<Request> specificationOf(RequestRegistryFilter filter) {
        Specification<Request> specification =
                RequestSpecifications.ofTenant(tenantContext.getCurrentTenantId());
        if (!filter.statuses().isEmpty()) {
            specification = specification.and(RequestSpecifications.inStatuses(filter.statuses()));
        }
        if (filter.typeId() != null) {
            specification = specification.and(RequestSpecifications.ofType(filter.typeId()));
        }
        if (filter.priority() != null) {
            specification = specification.and(RequestSpecifications.ofPriority(filter.priority()));
        }
        if (filter.createdFrom() != null) {
            specification = specification.and(
                    RequestSpecifications.createdFrom(filter.createdFrom()));
        }
        if (filter.createdTo() != null) {
            specification = specification.and(
                    RequestSpecifications.createdBefore(filter.createdTo()));
        }
        if (filter.overdueOnly()) {
            specification = specification.and(RequestSpecifications.overdue());
        }
        return specification;
    }

    /** Имена полей из запроса — в поля заявки; добавочный порядок по {@code id} не меняется. */
    private static Pageable withEntityFields(Pageable pageable) {
        Sort sort = Sort.by(pageable.getSort().stream()
                .map(order -> order.withProperty(
                        SORT_FIELDS.getOrDefault(order.getProperty(), order.getProperty())))
                .toList());
        return PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
    }
}
