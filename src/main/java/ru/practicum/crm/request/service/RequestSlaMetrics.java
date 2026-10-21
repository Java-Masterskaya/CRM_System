package ru.practicum.crm.request.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Счетчики сроков заявки (T-063): Метрики соблюдения сроков.*/
@Component
public class RequestSlaMetrics {

    static final String BREACHES = "crm.requests.sla.breached";
    static final String CLOSED = "crm.requests.closed";
    static final String DEADLINE = "deadline";
    static final String FIRST_RESPONSE = "first_response";
    static final String RESOLUTION = "resolution";
    static final String OUTCOME = "outcome";
    static final String ON_TIME = "on_time";
    static final String BREACHED = "breached";
    static final String TENANT = "tenant";

    private final Meter.MeterProvider<Counter> breaches;
    private final Meter.MeterProvider<Counter> closed;

    /**Регистрирует счетчики в общем реестре метрик приложения.*/
    public RequestSlaMetrics(MeterRegistry registry) {
        this.breaches = Counter.builder(BREACHES)
                .description("Нарушения срока заявки")
                .withRegistry(registry);

        this.closed = Counter.builder(CLOSED)
                .description("Закрытые заявки по соблюдению срока")
                .withRegistry(registry);
    }

    /** Истек срок первого ответа, заявка еще в {@code NEW}.*/
    public void firstResponseBreached(UUID tenantId) {
        breach(tenantId, FIRST_RESPONSE);
    }

    /** Истек срок решения, заявка еще не завершена.*/
    public void resolutionBreached(UUID tenantId) {
        breach(tenantId, RESOLUTION);
    }

    /**
     * Заявка переведенна в {@code DONE}. Отмена и отклонение сюда не входят: иначе они попадают
     * в 'закрытые в срок' и завышают цель.
     *
     * @param onTime {@code true}, если не один срок не был нарушен.*/
    public void closed(UUID tenantId, boolean onTime) {
        closed.withTags(OUTCOME, onTime ? ON_TIME : BREACHED, TENANT, tenantId.toString()
        ).increment();
    }

    private void breach(UUID tenantId, String deadline) {
        breaches.withTags(DEADLINE, deadline, TENANT, tenantId.toString()).increment();
    }
}
