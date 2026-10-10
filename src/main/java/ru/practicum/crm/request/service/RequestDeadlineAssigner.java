package ru.practicum.crm.request.service;

import org.springframework.stereotype.Service;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.sla.api.RequestDeadlineCalculator;

/**
 * Проставляет заявке сроки первого ответа и решения по SLA (T-058).
 *
 * <p>Вызывается при создании заявки (T-036): после сохранения, когда известен момент
 * создания, и в той же транзакции — сроки запишутся вместе с заявкой. Сроки отсчитываются от
 * момента создания, а не от момента вызова. Если посчитать их нельзя (нет политики, настроек
 * арендатора или рабочего времени), заявка остаётся без сроков, причину пишет в лог расчёт.
 */
@Service
public class RequestDeadlineAssigner {

    private final RequestDeadlineCalculator deadlines;

    public RequestDeadlineAssigner(RequestDeadlineCalculator deadlines) {
        this.deadlines = deadlines;
    }

    /**
     * Считает и проставляет сроки сохранённой заявке.
     *
     * @throws IllegalStateException если заявка ещё не сохранена: момента создания у неё нет
     */
    public void assignDeadlines(Request request) {
        if (request.getCreatedAt() == null) {
            throw new IllegalStateException(
                    "Сроки считаются от момента создания: сначала сохраните заявку");
        }
        deadlines.calculate(request.getTenantId(), request.getTypeId(), request.getPriority(),
                        request.getCreatedAt().toInstant())
                .ifPresent(calculated -> {
                    request.setFirstResponseDueAt(calculated.firstResponseDueAt());
                    request.setResolutionDueAt(calculated.resolutionDueAt());
                });
    }
}
