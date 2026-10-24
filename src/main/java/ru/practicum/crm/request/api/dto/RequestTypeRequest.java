package ru.practicum.crm.request.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import ru.practicum.crm.common.model.RequestPriority;

/**
 * Карточка типа заявки целиком — при создании и при изменении (T-034). Изменение заменяет все
 * поля: незаданное пояснение или приоритет по умолчанию становятся пустыми.
 *
 * <p>Пробелы по краям отбрасываются до проверки: « Выгрузка » и «Выгрузка» — одно название, а
 * пояснение из одних пробелов — его отсутствие.
 *
 * @param defaultPriority приоритет новой заявки этого типа; без него — {@code NORMAL}
 */
public record RequestTypeRequest(
        @NotBlank
        @Size(max = 100)
        String name,
        @Size(max = 500)
        String description,
        RequestPriority defaultPriority
) {
    public RequestTypeRequest {
        name = name == null ? null : name.strip();
        description = description == null || description.isBlank() ? null : description.strip();
    }
}
