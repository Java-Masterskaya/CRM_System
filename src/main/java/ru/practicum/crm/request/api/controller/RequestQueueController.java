package ru.practicum.crm.request.api.controller;

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
import ru.practicum.crm.request.api.dto.RequestRegistryItem;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.service.RequestQueueService;

/**
 * Очередь работ оператора (T-052): его незавершённые заявки, сначала более приоритетные.
 * Право на операцию проверяет сервис; клиент его не имеет и получает 403.
 *
 * <p>Параметры: {@code status} — только эти незавершённые статусы (можно несколько, через запятую
 * или повтором); страница — {@code page} и {@code size} по общему контракту списков. Порядок у
 * очереди один, поэтому {@code sort} не принимается — это ошибка валидации, а не молча
 * проигнорированный параметр.
 */
@RestController
@RequestMapping("/admin/queue")
public class RequestQueueController {

    private final RequestQueueService service;

    public RequestQueueController(RequestQueueService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<RequestRegistryItem> queue(
            @RequestParam(required = false) Set<RequestStatus> status,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        Pageable pageable = PageRequests.toPageable(page, size,
                sort == null ? null : List.of(sort), Set.of());
        return service.queue(status == null ? Set.of() : status, pageable);
    }
}
