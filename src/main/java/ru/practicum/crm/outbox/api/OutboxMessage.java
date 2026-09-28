package ru.practicum.crm.outbox.api;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Событие в том виде, в каком его получает отправитель: данные без состояния доставки.
 *
 * <p>Отправители живут в пакетах своего канала — письма в {@code notification}, вебхуки в
 * {@code integration}, — а сущность {@code OutboxEvent} за пределы пакета {@code outbox} не
 * выходит (SPEC §2.3). Поэтому обработчик передаёт отправителю эту копию.
 *
 * <p>Полезная нагрузка копируется и закрывается от изменений. Значения {@code null} в ней
 * допустимы — в JSON они бывают.
 *
 * @param id        идентификатор события; при повторной попытке тот же
 * @param tenantId  арендатор, которому принадлежит событие
 * @param eventType тип события
 * @param payload   полезная нагрузка
 * @param attempt   номер текущей попытки, начиная с единицы
 */
public record OutboxMessage(UUID id, UUID tenantId, String eventType,
        Map<String, Object> payload, int attempt) {

    public OutboxMessage {
        payload = Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
