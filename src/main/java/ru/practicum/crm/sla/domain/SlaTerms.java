package ru.practicum.crm.sla.domain;

/**
 * Сроки политики в минутах рабочего времени. Целые числа проще складывать и сравнивать, чем
 * текстовые интервалы; в какие часы идёт рабочее время, определяет календарь арендатора (T-056).
 *
 * @param firstResponseMinutes срок первого ответа
 * @param resolutionMinutes срок решения
 */
public record SlaTerms(int firstResponseMinutes, int resolutionMinutes) {
}
