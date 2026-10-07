package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.NotFoundException;
import ru.practicum.crm.sla.domain.WorkingCalendar;
import ru.practicum.crm.sla.domain.WorkingDay;

/**
 * Рабочий календарь на реальной базе (T-056): календарь по умолчанию, замена календаря,
 * изоляция арендаторов, часовой пояс из настроек арендатора, миграция и ограничения самой базы.
 *
 * <p>Арендаторы и их настройки создаются запросами SQL: пакет сроков не зависит от пакета
 * арендаторов, кроме его {@code api}. Что {@code TenantService} наполняет календарь при
 * создании арендатора, проверяет {@code TenantServiceIntegrationTest}.
 */
class WorkingCalendarIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610041730";
    private static final String PREVIOUS_MIGRATION = "202610021000";
    private static final UUID SYSTEM_TENANT =
            UUID.fromString("00000000-0000-0000-0000-000000000000");
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime SIX_PM = LocalTime.of(18, 0);
    /** Понедельник, 5 октября 2026 года: 07:30 UTC — 10:30 в Москве, 03:30 в Нью-Йорке. */
    private static final Instant MONDAY_MORNING_UTC =
            ZonedDateTime.of(2026, 10, 5, 7, 30, 0, 0, ZoneOffset.UTC).toInstant();

    @Autowired
    private WorkingCalendarService calendars;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant("Europe/Moscow");
        tenantB = insertTenant("Europe/Moscow");
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    /**
     * Миграция на базе, где арендаторы уже есть: каждый получает календарь по умолчанию, а
     * синтетический арендатор System — нет. Миграции прогоняются в отдельной схеме, чтобы не
     * трогать общую базу тестов.
     */
    @Test
    void migration_onDatabaseWithTenants_givesThemDefaultCalendarExceptSystem() {
        String schema = "migration_check_" + UUID.randomUUID().toString().replace("-", "");
        UUID existing = UUID.randomUUID();
        try {
            migrate(schema, PREVIOUS_MIGRATION);
            jdbcTemplate.update("INSERT INTO " + schema + ".tenants (id, name, active,"
                    + " created_at, updated_at) VALUES (?, 'Был до T-056', true, now(), now())",
                    existing);

            migrate(schema, null);

            assertThat(jdbcTemplate.queryForList("SELECT day_of_week FROM " + schema
                    + ".working_hours WHERE tenant_id = ? AND start_time = '09:00'"
                    + " AND end_time = '18:00'", String.class, existing))
                    .containsExactlyInAnyOrder(
                            "MONDAY", "TUESDAY", "WEDNESDAY", "THURSDAY", "FRIDAY");
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema
                    + ".tenants WHERE id = ?", Integer.class, SYSTEM_TENANT)).isEqualTo(1);
            assertThat(jdbcTemplate.queryForObject("SELECT count(*) FROM " + schema
                    + ".working_hours WHERE tenant_id = ?", Integer.class, SYSTEM_TENANT))
                    .isZero();
        } finally {
            jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    @Test
    void calendar_seededForNewTenant_isMondayToFridayNineToSixInTenantTimezone() {
        calendars.seedDefaults(tenantA);

        WorkingCalendar calendar = calendars.calendar(tenantA);

        assertThat(calendar.getZone()).isEqualTo(ZoneId.of("Europe/Moscow"));
        assertThat(calendar.getDays()).extracting(WorkingDay::dayOfWeek).containsExactly(
                DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                DayOfWeek.FRIDAY);
        assertThat(calendar.getDays())
                .allMatch(day -> day.start().equals(NINE) && day.end().equals(SIX_PM));
        assertThat(calendar.isWorkingTime(MONDAY_MORNING_UTC)).isTrue();
        assertThat(calendar.isWorkingTime(moscowTime(2026, 10, 10, 10, 30))).isFalse();
        assertThat(storedDays(tenantA)).isEqualTo(5);
    }

    @Test
    void changeWorkingDays_storesCalendarAndDaysLeftOutBecomeDaysOff() {
        calendars.changeWorkingDays(tenantA, List.of(
                new WorkingDay(DayOfWeek.MONDAY, NINE, SIX_PM),
                new WorkingDay(DayOfWeek.SATURDAY, LocalTime.of(10, 0), LocalTime.of(14, 0))));
        WorkingDay tuesday = new WorkingDay(DayOfWeek.TUESDAY, LocalTime.of(8, 0),
                LocalTime.of(12, 0));

        calendars.changeWorkingDays(tenantA, List.of(tuesday));

        assertThat(calendars.workingDays(tenantA)).containsExactly(tuesday);
        assertThat(storedDays(tenantA)).isEqualTo(1);
        WorkingCalendar calendar = calendars.calendar(tenantA);
        assertThat(calendar.isWorkingTime(MONDAY_MORNING_UTC)).isFalse();
        assertThat(calendar.isWorkingTime(moscowTime(2026, 10, 6, 8, 0))).isTrue();
    }

    @Test
    void changeWorkingDays_ofOneTenant_doesNotAffectAnother() {
        calendars.seedDefaults(tenantA);
        calendars.seedDefaults(tenantB);

        calendars.changeWorkingDays(tenantA, List.of(
                new WorkingDay(DayOfWeek.SUNDAY, NINE, SIX_PM)));

        assertThat(calendars.workingDays(tenantB)).hasSize(5)
                .noneMatch(day -> day.dayOfWeek() == DayOfWeek.SUNDAY);
        assertThat(storedDays(tenantB)).isEqualTo(5);
    }

    /** Повторное наполнение не затирает календарь, который администратор уже поменял. */
    @Test
    void seedDefaults_whenCalendarAlreadyChanged_keepsIt() {
        WorkingDay sunday = new WorkingDay(DayOfWeek.SUNDAY, NINE, SIX_PM);
        calendars.changeWorkingDays(tenantA, List.of(sunday));

        calendars.seedDefaults(tenantA);

        assertThat(calendars.workingDays(tenantA)).containsExactly(sunday);
    }

    /**
     * Тот же момент — 10:30 по Москве, рабочее время. После смены часового пояса арендатора
     * на Нью-Йорк это 03:30, ночь.
     */
    @Test
    void calendar_whenTenantTimezoneChanges_movesWorkingDayBoundaries() {
        calendars.seedDefaults(tenantA);
        assertThat(calendars.calendar(tenantA).isWorkingTime(MONDAY_MORNING_UTC)).isTrue();

        jdbcTemplate.update("UPDATE tenant_settings SET timezone = 'America/New_York'"
                + " WHERE tenant_id = ?", tenantA);

        assertThat(calendars.calendar(tenantA).isWorkingTime(MONDAY_MORNING_UTC)).isFalse();
    }

    @Test
    void calendar_whenTenantHasNoSettings_isNotFound() {
        UUID withoutSettings = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Без настроек', true, now(), now())", withoutSettings);

        NotFoundException thrown = catchThrowableOfType(
                () -> calendars.calendar(withoutSettings), NotFoundException.class);

        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
    }

    @Test
    void invertedInterval_insertedPastService_isRejectedByDatabase() {
        assertThatThrownBy(() -> insertDay(tenantA, "MONDAY", "18:00", "09:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("working_hours_interval_check");
    }

    /** Имя ограничения — то самое, по которому сервис узнаёт одновременную замену календаря. */
    @Test
    void secondIntervalForSameDay_insertedPastService_isRejectedByDatabase() {
        insertDay(tenantA, "MONDAY", "09:00", "13:00");

        assertThatThrownBy(() -> insertDay(tenantA, "MONDAY", "14:00", "18:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("working_hours_day_unique");
    }

    @Test
    void unknownDayOfWeek_insertedPastService_isRejectedByDatabase() {
        assertThatThrownBy(() -> insertDay(tenantA, "FUNDAY", "09:00", "18:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("working_hours_day_check");
    }

    /**
     * Три тысячи арендаторов по семь рабочих дней: календарь арендатора читается по индексу
     * уникальности, который начинается с арендатора, а не перебором таблицы.
     */
    @Test
    void calendarOfTenant_whenTableIsLarge_isReadByIndex() {
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " SELECT gen_random_uuid(), 'Арендатор ' || i, true, now(), now()"
                + " FROM generate_series(1, 3000) AS s(i)");
        jdbcTemplate.update("INSERT INTO working_hours (id, tenant_id, day_of_week, start_time,"
                + " end_time, created_at, updated_at)"
                + " SELECT gen_random_uuid(), t.id, (ARRAY['MONDAY', 'TUESDAY', 'WEDNESDAY',"
                + " 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'])[d], '09:00', '18:00', now(), now()"
                + " FROM tenants t CROSS JOIN generate_series(1, 7) AS s(d)");
        jdbcTemplate.execute("ANALYZE working_hours");

        List<String> plan = jdbcTemplate.queryForList("EXPLAIN SELECT * FROM working_hours"
                + " WHERE tenant_id = '" + tenantA + "'", String.class);

        assertThat(String.join("\n", plan))
                .as("план чтения календаря арендатора на таблице из 21 014 строк")
                .contains("working_hours_day_unique")
                .doesNotContain("Seq Scan");
    }

    private Integer storedDays(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM working_hours WHERE tenant_id = ?", Integer.class, tenantId);
    }

    private void insertDay(UUID tenantId, String day, String start, String end) {
        jdbcTemplate.update("INSERT INTO working_hours (id, tenant_id, day_of_week, start_time,"
                + " end_time, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?::time, ?::time, now(), now())",
                UUID.randomUUID(), tenantId, day, start, end);
    }

    private UUID insertTenant(String timezone) {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", id);
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, ?, now(), now())", id, timezone);
        return id;
    }

    /** Миграции в отдельной схеме: до версии {@code target} или до конца, если она не задана. */
    private static void migrate(String schema, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
                .schemas(schema)
                .locations("classpath:db/migration");
        if (target != null) {
            configuration.target(target);
        }
        configuration.load().migrate();
    }

    private static Instant moscowTime(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0,
                ZoneId.of("Europe/Moscow")).toInstant();
    }
}
