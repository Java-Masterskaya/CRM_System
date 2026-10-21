package ru.practicum.crm.request.api.client;

import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Set;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.request.api.client.dto.ClientRequestListItem;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.service.ClientRequestListService;

/**
 * Клиентский API для просмотра собственных заявок.
 */
@RestController
@RequestMapping("/client/requests")
public class ClientRequestListController {

    private static final String SORT_PARAM = "sort";

    private final ClientRequestListService service;

    public ClientRequestListController(ClientRequestListService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<ClientRequestListItem> list(
            @RequestParam(required = false) Set<RequestStatus> status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request
    ) {
        Pageable pageable = PageRequests.toPageableClampingSize(
                page,
                size,
                sortOf(request),
                ClientRequestListService.SORT_FIELDS.keySet());

        return service.list(status, pageable);
    }

    /**
     * Сохраняет формат sort=поле, направление, принятый в общем контракте списков.
     */
    private static List<String> sortOf(HttpServletRequest request) {
        String[] values = request.getParameterValues(SORT_PARAM);
        return values == null
                ? List.of(ClientRequestListService.DEFAULT_SORT)
                : List.of(values);
    }
}
