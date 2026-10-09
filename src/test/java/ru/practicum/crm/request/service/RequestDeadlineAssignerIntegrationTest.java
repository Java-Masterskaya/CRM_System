package ru.practicum.crm.request.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.request.domain.Request;
import ru.practicum.crm.request.domain.RequestStatus;
import ru.practicum.crm.request.repository.RequestRepository;

/**
 * Сроки заявки на реальной базе (T-058): так их будет проставлять создание заявки (T-036) —
 * сохранить заявку и в той же транзакции вызвать {@link RequestDeadlineAssigner}.
 *
 * <p>Настройки, календарь и политика арендатора создаются запросами SQL: пакет заявок не
 * зависит от пакета сроков, кроме его {@code api}.
 */
class RequestDeadlineAssignerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private RequestRepository requests;

    @Autowired
    private RequestDeadlineAssigner assigner;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;

    @BeforeEach
    void setUp() {
        tenantId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", tenantId);
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, 'Europe/Moscow', now(), now())", tenantId);
        jdbcTemplate.update("INSERT INTO working_hours (id, tenant_id, day_of_week, start_time,"
                + " end_time, created_at, updated_at)"
                + " SELECT gen_random_uuid(), ?, d, TIME '09:00', TIME '18:00', now(), now()"
                + " FROM unnest(ARRAY['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY'])"
                + " AS d", tenantId);
    }

    /**
     * DoD: сроки первого ответа и решения хранятся раздельно. Создание идёт «сейчас», поэтому
     * точные значения проверяет расчёт в пакете сроков; здесь — что оба записались и
     * упорядочены.
     */
    @Test
    void assignDeadlines_storesBothDeadlinesInRequestRow() {
        jdbcTemplate.update("INSERT INTO sla_policies (id, tenant_id, first_response_minutes,"
                + " resolution_minutes, created_at, updated_at)"
                + " VALUES (gen_random_uuid(), ?, 60, 480, now(), now())", tenantId);

        UUID id = createRequest();

        OffsetDateTime createdAt = column("created_at", id);
        OffsetDateTime firstResponse = column("first_response_due_at", id);
        OffsetDateTime resolution = column("resolution_due_at", id);
        assertThat(firstResponse).isAfter(createdAt);
        assertThat(resolution).isAfter(firstResponse);
    }

    /** DoD: без политики заявка сохраняется, а сроки остаются пустыми. */
    @Test
    void assignDeadlines_withoutPolicy_savesRequestWithoutDeadlines() {
        UUID id = createRequest();

        assertThat(column("first_response_due_at", id)).isNull();
        assertThat(column("resolution_due_at", id)).isNull();
    }

    /** Как создание заявки: сохранить и проставить сроки в одной транзакции. */
    private UUID createRequest() {
        return transactionTemplate.execute(status -> {
            Request request = requests.save(new Request(tenantId, UUID.randomUUID(),
                    "Выгрузка за квартал", "Нужен отчёт", RequestStatus.NEW));
            assigner.assignDeadlines(request);
            return request.getId();
        });
    }

    private OffsetDateTime column(String name, UUID requestId) {
        return jdbcTemplate.queryForObject("SELECT " + name + " FROM requests WHERE id = ?",
                OffsetDateTime.class, requestId);
    }
}
