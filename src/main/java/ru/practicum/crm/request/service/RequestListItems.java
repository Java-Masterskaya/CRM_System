package ru.practicum.crm.request.service;

import ru.practicum.crm.request.api.dto.RequestRegistryItem;
import ru.practicum.crm.request.domain.Request;

/**
 * Строка списка заявок административного контура: и реестра (T-040), и очереди оператора
 * (T-052). Поля одни и те же — по ним оператор выбирает заявку для работы.
 */
final class RequestListItems {

    private RequestListItems() {
    }

    static RequestRegistryItem of(Request request) {
        return new RequestRegistryItem(request.getId(), request.getSubject(), request.getStatus(),
                request.getPriority(), request.getTypeId(), request.getAuthorId(),
                request.getAssigneeId(), request.getCreatedAt(), request.getFirstResponseDueAt(),
                request.getResolutionDueAt(), request.isOverdue(), request.getVersion());
    }
}
