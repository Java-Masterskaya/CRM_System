package ru.practicum.crm.request.api.client.dto;

import java.util.UUID;

/**
 * Тип заявки, который клиент может выбрать при создании заявки (T-034). Только действующие типы
 * и без служебных полей (SPEC §5.2): признака активности, приоритета по умолчанию и дат.
 */
public record ClientRequestTypeItem(
        UUID id,
        String name,
        String description
) {
}
