package ru.practicum.crm.request.api.client;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.request.api.client.dto.ClientRequestTypeItem;
import ru.practicum.crm.request.domain.RequestType;
import ru.practicum.crm.request.service.RequestTypeService;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Типы заявок для выбора при создании заявки (T-034): только действующие, по названию. Отдельный
 * контроллер от административного (SPEC §5.1): другое право, другой набор полей.
 *
 * <p>Право {@code REQUEST_CREATE} проверяет сервис. Страница — по общему контракту списков,
 * {@code sort} не принимается: порядок один.
 */
@RestController
@RequestMapping("/client/request-types")
public class ClientRequestTypeController {

    private final RequestTypeService service;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public ClientRequestTypeController(RequestTypeService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public PageResponse<ClientRequestTypeItem> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return PageResponse.of(service.activeTypes(tenantContext.getCurrentTenantId(),
                        PageRequests.toPageable(page, size, sort == null ? null : List.of(sort),
                                Set.of()))
                .map(ClientRequestTypeController::toItem));
    }

    private static ClientRequestTypeItem toItem(RequestType type) {
        return new ClientRequestTypeItem(type.getId(), type.getName(), type.getDescription());
    }
}
