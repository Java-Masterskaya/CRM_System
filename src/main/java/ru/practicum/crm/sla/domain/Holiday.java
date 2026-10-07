package ru.practicum.crm.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.Getter;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Запись справочника нерабочих дней (T-057): дата, которая не следует недельному календарю.
 * Без часов работы — нерабочий день (праздник, корпоративный выходной); с часами — рабочий день
 * вместо выходного (перенос) или сокращённый день. Запись не меняется: чтобы исправить дату,
 * её удаляют и добавляют заново.
 */
@Entity
@Table(name = "holidays")
@Getter
public class Holiday extends TenantScopedEntity {

    @Column(name = "holiday_date", nullable = false, updatable = false)
    private LocalDate date;

    @Column(name = "description", nullable = false, updatable = false)
    private String description;

    @Column(name = "start_time", updatable = false)
    private LocalTime startTime;

    @Column(name = "end_time", updatable = false)
    private LocalTime endTime;

    protected Holiday() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует методы ниже.
    }

    private Holiday(UUID tenantId, LocalDate date, String description, LocalTime startTime,
            LocalTime endTime) {
        super(tenantId);
        this.date = date;
        this.description = description;
        this.startTime = startTime;
        this.endTime = endTime;
    }

    /** Нерабочий день: праздник или корпоративный выходной. */
    public static Holiday dayOff(UUID tenantId, LocalDate date, String description) {
        return new Holiday(tenantId, date, description, null, null);
    }

    /** Рабочий день с этими часами: перенос с выходного или сокращённый день. */
    public static Holiday workingDay(UUID tenantId, LocalDate date, String description,
            LocalTime start, LocalTime end) {
        return new Holiday(tenantId, date, description, start, end);
    }

    public boolean isWorking() {
        return startTime != null;
    }

    public DateOverride toDateOverride() {
        return new DateOverride(date, startTime, endTime);
    }
}
