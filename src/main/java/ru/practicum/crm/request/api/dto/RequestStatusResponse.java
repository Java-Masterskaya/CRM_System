package ru.practicum.crm.request.api.dto;

import java.util.UUID;
import ru.practicum.crm.request.domain.RequestStatus;

/**
 * Заявка после смены статуса: новый статус и новая версия — с ней клиент делает следующее
 * изменение.
 */
public record RequestStatusResponse(UUID id, RequestStatus status, long version) {
}
