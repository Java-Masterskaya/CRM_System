package ru.practicum.crm.assignment.api.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Запрос автоподбора исполнителя (T-051): кандидата машина выбирает сама, поэтому от клиента
 * нужна только версия заявки — та же, что при ручном назначении.
 */
public record AutoAssignExecutorRequest(@NotNull Long version) {
}
