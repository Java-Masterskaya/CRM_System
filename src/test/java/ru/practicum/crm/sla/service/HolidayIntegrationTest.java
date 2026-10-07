package ru.practicum.crm.sla.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import ru.practicum.crm.base.BaseIntegrationTest;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.sla.domain.Holiday;
import ru.practicum.crm.sla.domain.WorkingCalendar;

/**
 * Справочник нерабочих дней на реальной базе (T-057): праздник в будний день, перенос на
 * выходной, повтор даты, изоляция арендаторов и ограничения самой базы.
 *
 * <p>У обоих арендаторов календарь по умолчанию — понедельник–пятница, 09:00–18:00 по Москве.
 */
class HolidayIntegrationTest extends BaseIntegrationTest {

    private static final String MIGRATION_VERSION = "202610061050";
    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    /** Понедельник. */
    private static final LocalDate MONDAY = LocalDate.of(2026, 10, 5);
    /** Суббота. */
    private static final LocalDate SATURDAY = LocalDate.of(2026, 10, 10);

    @Autowired
    private HolidayService holidays;

    @Autowired
    private WorkingCalendarService calendars;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private UUID tenantA;
    private UUID tenantB;

    @BeforeEach
    void setUp() {
        tenantA = insertTenant();
        tenantB = insertTenant();
        calendars.seedDefaults(tenantA);
        calendars.seedDefaults(tenantB);
    }

    @Test
    void migration_whenApplied_isRecordedAsSuccessful() {
        assertThat(jdbcTemplate.queryForObject(
                "SELECT success FROM flyway_schema_history WHERE version = ?",
                Boolean.class, MIGRATION_VERSION)).isTrue();
    }

    @Test
    void calendar_whenWeekdayIsInDirectory_treatsItAsNonWorking() {
        holidays.createDayOff(tenantA, MONDAY, "Корпоративный выходной");

        WorkingCalendar calendar = calendars.calendar(tenantA);

        assertThat(calendar.isWorkingTime(moscowTime(MONDAY, 10, 30))).isFalse();
        assertThat(calendar.isWorkingTime(moscowTime(MONDAY.plusDays(1), 10, 30))).isTrue();
    }

    @Test
    void calendar_whenDayOffMarkedWorking_treatsItAsWorkingWithItsHours() {
        holidays.createWorkingDay(tenantA, SATURDAY, "Рабочая суббота", LocalTime.of(10, 0),
                LocalTime.of(14, 0));

        WorkingCalendar calendar = calendars.calendar(tenantA);

        assertThat(calendar.isWorkingTime(moscowTime(SATURDAY, 11, 0))).isTrue();
        assertThat(calendar.isWorkingTime(moscowTime(SATURDAY, 15, 0))).isFalse();
        assertThat(calendar.isWorkingTime(moscowTime(SATURDAY.plusDays(7), 11, 0))).isFalse();
    }

    @Test
    void createDayOff_whenDateAlreadyInDirectory_isRejectedAndStoredOnce() {
        holidays.createDayOff(tenantA, MONDAY, "Корпоративный выходной");

        ApiException asDayOff = catchThrowableOfType(
                () -> holidays.createDayOff(tenantA, MONDAY, "Ещё раз"), ApiException.class);
        ApiException asWorkingDay = catchThrowableOfType(() -> holidays.createWorkingDay(
                tenantA, MONDAY, "Ещё раз", LocalTime.of(10, 0), LocalTime.of(14, 0)),
                ApiException.class);

        assertThat(asDayOff.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(asWorkingDay.getErrorCode()).isEqualTo(ErrorCode.ALREADY_EXISTS);
        assertThat(storedDates(tenantA)).isEqualTo(1);
    }

    /** Имя ограничения — то самое, по которому сервис узнаёт повтор от одновременного запроса. */
    @Test
    void duplicateDate_insertedPastService_isRejectedByDatabase() {
        holidays.createDayOff(tenantA, MONDAY, "Корпоративный выходной");

        assertThatThrownBy(() -> insertHoliday(tenantA, MONDAY, "Копия", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("holidays_date_unique");
    }

    @Test
    void holidays_ofOneTenant_doNotAffectAnother() {
        Holiday ofA = holidays.createDayOff(tenantA, MONDAY, "Корпоративный выходной");

        assertThat(calendars.calendar(tenantB).isWorkingTime(moscowTime(MONDAY, 10, 30)))
                .isTrue();
        assertThat(firstPage(tenantB)).isEmpty();
        ApiException thrown = catchThrowableOfType(() -> holidays.delete(tenantB, ofA.getId()),
                ApiException.class);
        assertThat(thrown.getErrorCode()).isEqualTo(ErrorCode.NOT_FOUND);
        assertThat(storedDates(tenantA)).isEqualTo(1);
    }

    @Test
    void delete_returnsDateToWeeklyCalendar() {
        Holiday holiday = holidays.createDayOff(tenantA, MONDAY, "Корпоративный выходной");

        holidays.delete(tenantA, holiday.getId());

        assertThat(storedDates(tenantA)).isZero();
        assertThat(calendars.calendar(tenantA).isWorkingTime(moscowTime(MONDAY, 10, 30)))
                .isTrue();
    }

    @Test
    void holidays_areListedInDateOrder() {
        holidays.createDayOff(tenantA, LocalDate.of(2027, 1, 1), "Новый год");
        holidays.createWorkingDay(tenantA, SATURDAY, "Рабочая суббота", LocalTime.of(10, 0),
                LocalTime.of(14, 0));
        holidays.createDayOff(tenantA, MONDAY, "Корпоративный выходной");

        assertThat(firstPage(tenantA)).extracting(Holiday::getDate)
                .containsExactly(MONDAY, SATURDAY, LocalDate.of(2027, 1, 1));
    }

    @Test
    void hoursWithoutPair_insertedPastService_areRejectedByDatabase() {
        assertThatThrownBy(() -> insertHoliday(tenantA, SATURDAY, "Перенос", "10:00", null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("holidays_hours_check");
    }

    @Test
    void invertedHours_insertedPastService_areRejectedByDatabase() {
        assertThatThrownBy(() -> insertHoliday(tenantA, SATURDAY, "Перенос", "14:00", "10:00"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("holidays_interval_check");
    }

    @Test
    void blankDescription_insertedPastService_isRejectedByDatabase() {
        assertThatThrownBy(() -> insertHoliday(tenantA, MONDAY, " ", null, null))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("holidays_description_check");
    }

    /**
     * Двадцать тысяч дат у одного арендатора и три у другого: справочник арендатора читается по
     * индексу уникальности, который начинается с арендатора и даты, а не перебором таблицы.
     */
    @Test
    void holidaysOfTenant_whenTableIsLarge_areReadByIndex() {
        jdbcTemplate.update("INSERT INTO holidays (id, tenant_id, holiday_date, description,"
                + " created_at, updated_at)"
                + " SELECT gen_random_uuid(), ?, DATE '1970-01-01' + i, 'Дата ' || i, now(), now()"
                + " FROM generate_series(1, 20000) AS s(i)", tenantB);
        for (int day = 1; day <= 3; day++) {
            insertHoliday(tenantA, MONDAY.plusDays(day), "Дата " + day, null, null);
        }
        jdbcTemplate.execute("ANALYZE holidays");

        List<String> plan = jdbcTemplate.queryForList("EXPLAIN SELECT * FROM holidays"
                + " WHERE tenant_id = '" + tenantA + "' ORDER BY holiday_date LIMIT 20",
                String.class);

        assertThat(String.join("\n", plan))
                .as("план страницы справочника на таблице из 20 003 дат")
                .contains("holidays_date_unique")
                .doesNotContain("Seq Scan");
    }

    private List<Holiday> firstPage(UUID tenantId) {
        return holidays.holidays(tenantId, PageRequest.of(0, 20)).getContent();
    }

    private Integer storedDates(UUID tenantId) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM holidays WHERE tenant_id = ?", Integer.class, tenantId);
    }

    private void insertHoliday(UUID tenantId, LocalDate date, String description, String start,
            String end) {
        jdbcTemplate.update("INSERT INTO holidays (id, tenant_id, holiday_date, description,"
                + " start_time, end_time, created_at, updated_at)"
                + " VALUES (?, ?, ?, ?, ?::time, ?::time, now(), now())",
                UUID.randomUUID(), tenantId, date, description, start, end);
    }

    private UUID insertTenant() {
        UUID id = UUID.randomUUID();
        jdbcTemplate.update("INSERT INTO tenants (id, name, active, created_at, updated_at)"
                + " VALUES (?, 'Арендатор', true, now(), now())", id);
        jdbcTemplate.update("INSERT INTO tenant_settings (tenant_id, timezone, created_at,"
                + " updated_at) VALUES (?, 'Europe/Moscow', now(), now())", id);
        return id;
    }

    private static Instant moscowTime(LocalDate date, int hour, int minute) {
        return ZonedDateTime.of(date, LocalTime.of(hour, minute), MOSCOW).toInstant();
    }
}
