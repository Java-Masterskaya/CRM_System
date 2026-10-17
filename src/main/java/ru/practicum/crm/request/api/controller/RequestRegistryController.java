package ru.practicum.crm.request.api.controller;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.request.api.dto.RequestRegistryFilter;
import ru.practicum.crm.request.api.dto.RequestRegistryItem;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.service.RequestRegistryService;

/**
 * Реестр заявок для оператора и администратора (T-040). Право на чтение всех заявок
 * арендатора проверяет сервис; клиент его не имеет и получает 403.
 *
 * <p>Параметры: {@code status} (можно несколько, через запятую или повтором), {@code typeId},
 * {@code priority}, {@code createdFrom} и {@code createdTo} (момент ISO-8601, правая граница не
 * входит), {@code overdue=true} — только просроченные сейчас; страница — по общему контракту
 * списков: {@code page}, {@code size}, {@code sort=поле,asc|desc}. Неверное значение любого
 * параметра — ошибка валидации с его именем, а не пустой список.
 */
@RestController
@RequestMapping("/admin/requests")
public class RequestRegistryController {

    private static final String SORT_PARAM = "sort";

    private final RequestRegistryService service;

    public RequestRegistryController(RequestRegistryService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<RequestRegistryItem> list(
            @RequestParam(required = false) Set<RequestStatus> status,
            @RequestParam(required = false) UUID typeId,
            @RequestParam(required = false) RequestPriority priority,
            @RequestParam(required = false) Instant createdFrom,
            @RequestParam(required = false) Instant createdTo,
            @RequestParam(defaultValue = "false") boolean overdue,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        Pageable pageable = PageRequests.toPageable(page, size, sortOf(request),
                RequestRegistryService.SORT_FIELDS.keySet());
        return service.list(new RequestRegistryFilter(status, typeId, priority, createdFrom,
                createdTo, overdue), pageable);
    }

    /**
     * Сортировка читается из запроса как есть: при привязке к списку Spring разрезал бы
     * единственное значение {@code createdAt,desc} по запятой на два поля.
     */
    private static List<String> sortOf(HttpServletRequest request) {
        String[] values = request.getParameterValues(SORT_PARAM);
        return values == null ? List.of(RequestRegistryService.DEFAULT_SORT) : List.of(values);
    }
}
