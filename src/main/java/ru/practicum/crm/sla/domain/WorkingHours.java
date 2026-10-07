package ru.practicum.crm.sla.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.time.DayOfWeek;
import java.time.LocalTime;
import java.util.UUID;
import ru.practicum.crm.common.model.TenantScopedEntity;

/**
 * Строка рабочего календаря (T-056): рабочие часы арендатора в один день недели. Строки не
 * меняются — календарь заменяется целиком.
 */
@Entity
@Table(name = "working_hours")
public class WorkingHours extends TenantScopedEntity {

    @Enumerated(EnumType.STRING)
    @Column(name = "day_of_week", nullable = false, updatable = false, length = 9)
    private DayOfWeek dayOfWeek;

    @Column(name = "start_time", nullable = false, updatable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false, updatable = false)
    private LocalTime endTime;

    protected WorkingHours() {
        // Конструктор без аргументов нужен Hibernate; прикладной код использует конструктор ниже.
    }

    public WorkingHours(UUID tenantId, WorkingDay day) {
        super(tenantId);
        this.dayOfWeek = day.dayOfWeek();
        this.startTime = day.start();
        this.endTime = day.end();
    }

    public WorkingDay toWorkingDay() {
        return new WorkingDay(dayOfWeek, startTime, endTime);
    }
}
