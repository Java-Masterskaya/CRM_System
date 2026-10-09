package ru.practicum.crm.sla.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Календарь без базы: день недели, даты справочника, время суток и часовой пояс. */
class WorkingCalendarTest {

    private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");
    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime SIX_PM = LocalTime.of(18, 0);
    private static final List<WorkingDay> WEEKDAYS = Stream.of(DayOfWeek.MONDAY,
                    DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY,
                    DayOfWeek.FRIDAY)
            .map(day -> new WorkingDay(day, NINE, SIX_PM))
            .toList();

    private final WorkingCalendar moscow = new WorkingCalendar(MOSCOW, WEEKDAYS);

    @Test
    void isWorkingTime_insideWorkingHoursOfWorkingDay_isTrue() {
        assertThat(moscow.isWorkingTime(moscowTime(2026, 10, 5, 10, 30))).isTrue();
    }

    @Test
    void isWorkingTime_atStartOfDay_isTrueAndAtEnd_isFalse() {
        assertThat(moscow.isWorkingTime(moscowTime(2026, 10, 5, 9, 0))).isTrue();
        assertThat(moscow.isWorkingTime(moscowTime(2026, 10, 5, 17, 59))).isTrue();
        assertThat(moscow.isWorkingTime(moscowTime(2026, 10, 5, 18, 0))).isFalse();
    }

    @Test
    void isWorkingTime_onDayOff_isFalse() {
        assertThat(moscow.isWorkingTime(moscowTime(2026, 10, 10, 10, 30))).isFalse();
    }

    /** 22:30 воскресенья по UTC — в Москве уже 01:30 понедельника. */
    @Test
    void isWorkingTime_takesDayOfWeekInTenantTimezone() {
        WorkingCalendar mondayNight = new WorkingCalendar(MOSCOW, List.of(
                new WorkingDay(DayOfWeek.MONDAY, LocalTime.MIDNIGHT, LocalTime.of(2, 0))));
        Instant sundayEveningUtc = ZonedDateTime.of(2026, 10, 4, 22, 30, 0, 0, ZoneOffset.UTC)
                .toInstant();

        assertThat(mondayNight.isWorkingTime(sundayEveningUtc)).isTrue();
        assertThat(new WorkingCalendar(ZoneOffset.UTC, mondayNight.getDays())
                .isWorkingTime(sundayEveningUtc)).isFalse();
    }

    /**
     * Зимой Берлин живёт по UTC+1, летом — по UTC+2. Рабочий день остаётся 09:00–18:00 по
     * местным часам, поэтому один и тот же момент 07:30 UTC зимой ещё нерабочий (08:30), а
     * летом уже рабочий (09:30).
     */
    @Test
    void isWorkingTime_acrossDaylightSavingChange_followsLocalHours() {
        WorkingCalendar berlin = new WorkingCalendar(BERLIN, WEEKDAYS);

        assertThat(berlin.isWorkingTime(utc(2026, 1, 5, 7, 30))).isFalse();
        assertThat(berlin.isWorkingTime(utc(2026, 7, 6, 7, 30))).isTrue();
        assertThat(berlin.isWorkingTime(utc(2026, 1, 5, 16, 30))).isTrue();
        assertThat(berlin.isWorkingTime(utc(2026, 7, 6, 16, 30))).isFalse();
    }

    /** Понедельник 5 октября 2026 года — праздник: нерабочий, хотя по календарю будний. */
    @Test
    void isWorkingTime_onHolidayFallingOnWorkingDay_isFalse() {
        WorkingCalendar withHoliday = new WorkingCalendar(MOSCOW, WEEKDAYS,
                List.of(new DateOverride(LocalDate.of(2026, 10, 5), null, null)));

        assertThat(withHoliday.isWorkingTime(moscowTime(2026, 10, 5, 10, 30))).isFalse();
        assertThat(withHoliday.isWorkingTime(moscowTime(2026, 10, 6, 10, 30))).isTrue();
    }

    /** Суббота 10 октября 2026 года — перенос: рабочая с 10:00 до 14:00 из справочника. */
    @Test
    void isWorkingTime_onDayOffMarkedWorking_followsHoursOfThatDate() {
        WorkingCalendar withTransfer = new WorkingCalendar(MOSCOW, WEEKDAYS, List.of(
                new DateOverride(LocalDate.of(2026, 10, 10), LocalTime.of(10, 0),
                        LocalTime.of(14, 0))));

        assertThat(withTransfer.isWorkingTime(moscowTime(2026, 10, 10, 9, 59))).isFalse();
        assertThat(withTransfer.isWorkingTime(moscowTime(2026, 10, 10, 10, 0))).isTrue();
        assertThat(withTransfer.isWorkingTime(moscowTime(2026, 10, 10, 13, 59))).isTrue();
        assertThat(withTransfer.isWorkingTime(moscowTime(2026, 10, 10, 14, 0))).isFalse();
        assertThat(withTransfer.isWorkingTime(moscowTime(2026, 10, 17, 11, 0))).isFalse();
    }

    /** 22:30 воскресенья по UTC — в Москве уже понедельник, и праздник понедельника действует. */
    @Test
    void isWorkingTime_takesHolidayDateInTenantTimezone() {
        List<WorkingDay> mondayNight = List.of(
                new WorkingDay(DayOfWeek.MONDAY, LocalTime.MIDNIGHT, LocalTime.of(2, 0)));
        Instant sundayEveningUtc = utc(2026, 10, 4, 22, 30);

        assertThat(new WorkingCalendar(MOSCOW, mondayNight, List.of(
                new DateOverride(LocalDate.of(2026, 10, 5), null, null)))
                .isWorkingTime(sundayEveningUtc)).isFalse();
        assertThat(new WorkingCalendar(MOSCOW, mondayNight, List.of(
                new DateOverride(LocalDate.of(2026, 10, 4), null, null)))
                .isWorkingTime(sundayEveningUtc)).isTrue();
    }

    @Test
    void getDays_areOrderedFromMondayWhateverTheInputOrder() {
        WorkingDay friday = new WorkingDay(DayOfWeek.FRIDAY, NINE, SIX_PM);
        WorkingDay monday = new WorkingDay(DayOfWeek.MONDAY, NINE, SIX_PM);

        assertThat(new WorkingCalendar(MOSCOW, List.of(friday, monday)).getDays())
                .containsExactly(monday, friday);
    }

    /** DoD T-058: за час до конца рабочего дня со сроком 8 часов — следующий рабочий день. */
    @Test
    void plusWorkingMinutes_eightHoursFromHourBeforeEndOfDay_endsNextWorkingDay() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 5, 17, 0), 480))
                .contains(moscowTime(2026, 10, 6, 16, 0));
    }

    @Test
    void plusWorkingMinutes_withinOneWorkingDay_addsMinutesAsIs() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 5, 10, 0), 120))
                .contains(moscowTime(2026, 10, 5, 12, 0));
    }

    @Test
    void plusWorkingMinutes_endingExactlyAtEndOfDay_isThatEnd() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 5, 17, 0), 60))
                .contains(moscowTime(2026, 10, 5, 18, 0));
    }

    @Test
    void plusWorkingMinutes_fromBeforeStartOfDay_countsFromStart() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 5, 7, 0), 60))
                .contains(moscowTime(2026, 10, 5, 10, 0));
    }

    /** DoD T-058: заявка, созданная в субботу, отсчитывает срок с утра понедельника. */
    @Test
    void plusWorkingMinutes_fromDayOff_countsFromStartOfNextWorkingDay() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 10, 12, 0), 60))
                .contains(moscowTime(2026, 10, 12, 10, 0));
    }

    @Test
    void plusWorkingMinutes_overWeekend_skipsIt() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 9, 17, 0), 120))
                .contains(moscowTime(2026, 10, 12, 10, 0));
    }

    /** DoD T-058: праздник во вторник сдвигает срок на среду. */
    @Test
    void plusWorkingMinutes_overHoliday_movesDeadlineForward() {
        WorkingCalendar withHoliday = new WorkingCalendar(MOSCOW, WEEKDAYS,
                List.of(new DateOverride(LocalDate.of(2026, 10, 6), null, null)));

        assertThat(withHoliday.plusWorkingMinutes(moscowTime(2026, 10, 5, 17, 0), 480))
                .contains(moscowTime(2026, 10, 7, 16, 0));
    }

    /** Рабочая суббота 10:00–14:00 считается: срок с вечера пятницы — в субботу. */
    @Test
    void plusWorkingMinutes_overDayOffMarkedWorking_usesItsHours() {
        WorkingCalendar withTransfer = new WorkingCalendar(MOSCOW, WEEKDAYS, List.of(
                new DateOverride(LocalDate.of(2026, 10, 10), LocalTime.of(10, 0),
                        LocalTime.of(14, 0))));

        assertThat(withTransfer.plusWorkingMinutes(moscowTime(2026, 10, 9, 17, 0), 120))
                .contains(moscowTime(2026, 10, 10, 11, 0));
    }

    @Test
    void plusWorkingMinutes_keepsSecondsOfStart() {
        Instant halfMinutePastFive =
                ZonedDateTime.of(2026, 10, 5, 17, 0, 30, 0, MOSCOW).toInstant();

        assertThat(moscow.plusWorkingMinutes(halfMinutePastFive, 60)).contains(
                ZonedDateTime.of(2026, 10, 6, 9, 0, 30, 0, MOSCOW).toInstant());
    }

    /** 40 рабочих часов с утра понедельника: четыре полных дня по 9 часов и 4 часа пятницы. */
    @Test
    void plusWorkingMinutes_overSeveralDays_addsWholeDays() {
        assertThat(moscow.plusWorkingMinutes(moscowTime(2026, 10, 5, 9, 0), 2400))
                .contains(moscowTime(2026, 10, 9, 13, 0));
    }

    /**
     * В ночь на 29 марта 2026 года Берлин переходит на летнее время. Полчаса пятницы и полчаса
     * понедельника по местным часам: срок — 09:30 понедельника, то есть 07:30 UTC. Сложение по
     * UTC дало бы 08:30 UTC — на час позже.
     */
    @Test
    void plusWorkingMinutes_acrossDaylightSavingChange_followsLocalHours() {
        WorkingCalendar berlin = new WorkingCalendar(BERLIN, WEEKDAYS);

        assertThat(berlin.plusWorkingMinutes(Instant.parse("2026-03-27T16:30:00Z"), 60))
                .contains(Instant.parse("2026-03-30T07:30:00Z"));
    }

    @Test
    void plusWorkingMinutes_whenCalendarHasNoWorkingTimeAhead_isEmpty() {
        LocalDate saturday = LocalDate.of(2026, 10, 10);
        DateOverride workingSaturday = new DateOverride(saturday, LocalTime.of(10, 0),
                LocalTime.of(14, 0));
        Instant fridayEvening = moscowTime(2026, 10, 9, 17, 0);

        assertThat(new WorkingCalendar(MOSCOW, List.of())
                .plusWorkingMinutes(fridayEvening, 60)).isEmpty();
        assertThat(new WorkingCalendar(MOSCOW, List.of(), List.of(workingSaturday))
                .plusWorkingMinutes(moscowTime(2026, 10, 12, 9, 0), 60)).isEmpty();
        assertThat(new WorkingCalendar(MOSCOW, List.of(), List.of(workingSaturday))
                .plusWorkingMinutes(fridayEvening, 60)).contains(moscowTime(2026, 10, 10, 11, 0));
    }

    @Test
    void plusWorkingMinutes_withNonPositiveTerm_isRejected() {
        assertThatThrownBy(() -> moscow.plusWorkingMinutes(moscowTime(2026, 10, 5, 10, 0), 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Instant moscowTime(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, MOSCOW).toInstant();
    }

    private static Instant utc(int year, int month, int day, int hour, int minute) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, ZoneOffset.UTC)
                .toInstant();
    }
}
