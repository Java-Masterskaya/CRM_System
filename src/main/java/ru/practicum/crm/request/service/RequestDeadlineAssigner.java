package ru.practicum.crm.request.service;

import static net.logstash.logback.argument.StructuredArguments.value;

import java.time.Instant;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import ru.practicum.crm.request.domain.ReopenedDeadlines;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.sla.api.RequestDeadlineCalculator;
import ru.practicum.crm.sla.api.RequestDeadlines;

/**
 * Проставляет заявке сроки первого ответа и решения по SLA (T-058) и пересчитывает их, когда у
 * заявки меняются приоритет или тип (T-062).
 *
 * <p>Вызывается при создании заявки (T-036): после сохранения, когда известен момент
 * создания, и в той же транзакции — сроки запишутся вместе с заявкой. Сроки отсчитываются от
 * момента создания, а не от момента вызова. Если посчитать их нельзя (нет политики, настроек
 * арендатора или рабочего времени), заявка остаётся без сроков, причину пишет в лог расчёт.
 */
@Service
public class RequestDeadlineAssigner {

    private static final Logger LOG = LoggerFactory.getLogger(RequestDeadlineAssigner.class);

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
        calculate(request).ifPresent(calculated -> {
            request.setFirstResponseDueAt(calculated.firstResponseDueAt());
            request.setResolutionDueAt(calculated.resolutionDueAt());
        });
    }

    /**
     * Пересчитывает сроки по политике новых приоритета и типа (T-062) — тоже от момента создания:
     * если считать от момента правки, каждая правка отодвигала бы срок заново. Какие сроки ещё
     * переносятся и какие признаки просрочки при этом снимаются, решает
     * {@link Request#moveDeadlines}.
     *
     * <p>Если посчитать сроки нельзя, они остаются прежними: заявка не теряет сроков из-за правки.
     * Факт пишется в лог — причину перед этим пишет расчёт.
     *
     * @param now момент правки: срок позже него ещё не наступил
     * @return по каким срокам снят признак просрочки; у них надо снять и отметку эскалации
     */
    public ReopenedDeadlines reassignDeadlines(Request request, Instant now) {
        Optional<RequestDeadlines> calculated = calculate(request);
        if (calculated.isEmpty()) {
            LOG.info("Сроки заявки {} не пересчитаны после смены приоритета или типа: остаются"
                    + " прежними", value("requestId", request.getId()));
            return ReopenedDeadlines.NONE;
        }
        return request.moveDeadlines(calculated.get().firstResponseDueAt(),
                calculated.get().resolutionDueAt(), now);
    }

    private Optional<RequestDeadlines> calculate(Request request) {
        if (request.getCreatedAt() == null) {
            throw new IllegalStateException(
                    "Сроки считаются от момента создания: сначала сохраните заявку");
        }
        return deadlines.calculate(request.getTenantId(), request.getTypeId(),
                request.getPriority(), request.getCreatedAt().toInstant());
    }
}
