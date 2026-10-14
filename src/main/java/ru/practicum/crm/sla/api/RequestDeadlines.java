package ru.practicum.crm.sla.api;

import java.time.Instant;

/**
 * Сроки заявки по политике сроков и рабочему календарю (T-058).
 *
 * @param firstResponseDueAt срок первого ответа
 * @param resolutionDueAt срок решения
 */
public record RequestDeadlines(Instant firstResponseDueAt, Instant resolutionDueAt) {
}
