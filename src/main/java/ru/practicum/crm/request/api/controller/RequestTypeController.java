package ru.practicum.crm.request.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.request.api.dto.RequestTypeRequest;
import ru.practicum.crm.request.api.dto.RequestTypeResponse;
import ru.practicum.crm.request.domain.RequestType;
import ru.practicum.crm.request.service.RequestTypeService;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Справочник типов заявок для администратора арендатора (T-034): просмотр, создание, изменение,
 * отключение и включение. Права проверяет сервис: менять справочник может только носитель
 * {@code REQUEST_TYPE_MANAGE}, остальные получают 403.
 *
 * <p>Список — страница по общему контракту, по названию. Порядок у справочника один, поэтому
 * {@code sort} не принимается — это ошибка валидации, а не молча проигнорированный параметр.
 * Удаления нет: тип, на который ссылаются заявки, только отключается.
 */
@RestController
@RequestMapping("/admin/request-types")
public class RequestTypeController {

    private final RequestTypeService service;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public RequestTypeController(RequestTypeService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public PageResponse<RequestTypeResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return PageResponse.of(service.types(tenantContext.getCurrentTenantId(),
                        PageRequests.toPageable(page, size, sort == null ? null : List.of(sort),
                                Set.of()))
                .map(RequestTypeController::toResponse));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RequestTypeResponse create(@Valid @RequestBody RequestTypeRequest body) {
        return toResponse(service.create(tenantContext.getCurrentTenantId(), body.name(),
                body.description(), body.defaultPriority()));
    }

    @PutMapping("/{typeId}")
    public RequestTypeResponse update(@PathVariable UUID typeId,
            @Valid @RequestBody RequestTypeRequest body) {
        return toResponse(service.update(tenantContext.getCurrentTenantId(), typeId, body.name(),
                body.description(), body.defaultPriority()));
    }

    /** Отключённый тип нельзя выбрать для новой заявки; повторное отключение — не ошибка. */
    @PostMapping("/{typeId}/deactivate")
    public RequestTypeResponse deactivate(@PathVariable UUID typeId) {
        return toResponse(service.deactivate(tenantContext.getCurrentTenantId(), typeId));
    }

    @PostMapping("/{typeId}/activate")
    public RequestTypeResponse activate(@PathVariable UUID typeId) {
        return toResponse(service.activate(tenantContext.getCurrentTenantId(), typeId));
    }

    private static RequestTypeResponse toResponse(RequestType type) {
        return new RequestTypeResponse(type.getId(), type.getName(), type.getDescription(),
                type.getDefaultPriority(), type.isActive(), type.getCreatedAt(),
                type.getUpdatedAt());
    }
}
