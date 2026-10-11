package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.model.RequestPriority;
import ru.practicum.crm.sla.api.RequestDeadlineCalculator;
import ru.practicum.crm.sla.api.RequestDeadlines;
import ru.practicum.crm.sla.domain.SlaTerms;

/**
 * Расчёт сроков на реальной базе (T-058): политика, календарь по умолчанию и справочник
 * нерабочих дней вместе — и случаи, когда сроков нет.
 *
 * <p>Календарь арендатора по умолчанию — понедельник–пятница, 09:00–18:00 по Москве.
 */
class RequestDeadlineIntegrationTest extends BaseIntegrationTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Понедельник, 5 октября 2026 года, 17:00 по Москве — за час до конца рабочего дня. */
    private static final Instant MONDAY_FIVE_PM = moscowTime(5, 17);
    /** Час на первый ответ, 8 рабочих часов на решение. */
    private static final SlaTerms HOUR_AND_EIGHT_HOURS = new SlaTerms(60, 480);

    @Autowired
    private RequestDeadlineCalculator deadlines;

    @Autowired
    private SlaPolicyService policies;

    @Autowired
    private WorkingCalendarService calendars;

    @Autowired
    private HolidayService holidays;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantId;
    private UUID typeId;

    @BeforeEach
    void setUp() {
        tenantId = insertTenant("Арендатор");
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, 'Europe/Moscow', now(), now())", tenantId);
        calendars.seedDefaults(tenantId);
        typeId = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO request_types (id, tenant_id, name, active, created_at,"
                + " updated_at) VALUES (?, ?, 'Выгрузка', true, now(), now())", typeId, tenantId);
    }

    /** DoD: 8 часов с 17:00 понедельника — 16:00 вторника, а не 01:00 ночи. */
    @Test
    void calculate_byPolicyOfTypeAndPriority_countsInTenantWorkingTime() {
        policies.create(tenantId, typeId, RequestPriority.HIGH, HOUR_AND_EIGHT_HOURS);

        assertThat(deadlines.calculate(tenantId, typeId, RequestPriority.HIGH, MONDAY_FIVE_PM))
                .contains(new RequestDeadlines(moscowTime(5, 18), moscowTime(6, 16)));
    }

    /** DoD: праздник во вторник сдвигает срок решения на среду. */
    @Test
    void calculate_overHolidayFromDirectory_movesDeadlineForward() {
        policies.create(tenantId, typeId, RequestPriority.HIGH, HOUR_AND_EIGHT_HOURS);
        holidays.createDayOff(tenantId, LocalDate.of(2026, 10, 6), "Корпоративный выходной");

        assertThat(deadlines.calculate(tenantId, typeId, RequestPriority.HIGH, MONDAY_FIVE_PM))
                .contains(new RequestDeadlines(moscowTime(5, 18), moscowTime(7, 16)));
    }

    @Test
    void calculate_withoutPolicyOfPairOrDefault_isEmpty() {
        assertThat(deadlines.calculate(tenantId, typeId, RequestPriority.HIGH, MONDAY_FIVE_PM))
                .isEmpty();
    }

    /**
     * Расчёт вызывается внутри транзакции создания заявки. У арендатора без настроек сроков нет,
     * и транзакция вызывающего должна зафиксироваться: если бы по дороге из транзакционного
     * метода вылетело исключение, Spring пометил бы её на откат, и фиксация упала бы с
     * {@code UnexpectedRollbackException}.
     */
    @Test
    void calculate_forTenantWithoutSettings_isEmptyAndCallersTransactionCommits() {
        UUID withoutSettings = insertTenant("Без настроек");
        policies.createDefault(withoutSettings, HOUR_AND_EIGHT_HOURS);

        Optional<RequestDeadlines> calculated = transactionTemplate.execute(status ->
                deadlines.calculate(withoutSettings, null, RequestPriority.NORMAL,
                        MONDAY_FIVE_PM));

        assertThat(calculated).isEmpty();
    }

    /** Арендатор без настроек; slug уникален и подходит под формат из T-025. */
    private UUID insertTenant(String name) {
        UUID id = UUID.randomUUID();
        String slug = "tenant-" + id.toString().replace("-", "");
        jdbcTemplate.update("INSERT INTO tenants (id, name, slug, active, created_at, updated_at)"
                + " VALUES (?, ?, ?, true, now(), now())", id, name, slug);
        return id;
    }

    private static Instant moscowTime(int dayOfOctober, int hour) {
        return ZonedDateTime.of(2026, 10, dayOfOctober, hour, 0, 0, 0, MOSCOW).toInstant();
    }
}
