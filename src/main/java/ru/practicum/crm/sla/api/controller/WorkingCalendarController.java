package ru.practicum.crm.sla.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.sla.api.dto.WorkingWeekDto;
import ru.practicum.crm.sla.domain.WorkingDay;
import ru.practicum.crm.sla.service.WorkingCalendarService;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Рабочие часы арендатора для администратора (T-056): просмотр и замена недели целиком. Право
 * {@code SLA_POLICY_MANAGE} проверяет сервис, без него — 403.
 *
 * <p>Замена целиком, а не по дню: дни, которых нет в запросе, становятся выходными, — так
 * администратор видит и сохраняет неделю такой, какой она будет в расчёте сроков.
 */
@RestController
@RequestMapping("/admin/calendar/working-hours")
public class WorkingCalendarController {

    private final WorkingCalendarService service;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public WorkingCalendarController(WorkingCalendarService service,
            TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public WorkingWeekDto workingDays() {
        return toDto(service.workingDays(tenantContext.getCurrentTenantId()));
    }

    @PutMapping
    public WorkingWeekDto changeWorkingDays(@RequestBody WorkingWeekDto body) {
        // Без списка — та же ошибка, что у пустого: у недели нет ни одного рабочего дня.
        List<WorkingDay> days = body.days() == null ? List.of() : body.days().stream()
                .map(day -> day == null ? null
                        : new WorkingDay(day.dayOfWeek(), day.start(), day.end()))
                .toList();
        return toDto(service.changeWorkingDays(tenantContext.getCurrentTenantId(), days));
    }

    private static WorkingWeekDto toDto(List<WorkingDay> days) {
        return new WorkingWeekDto(days.stream()
                .map(day -> new WorkingWeekDto.Day(day.dayOfWeek(), day.start(), day.end()))
                .toList());
    }
}
