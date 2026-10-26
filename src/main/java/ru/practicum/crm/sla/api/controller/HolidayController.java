package ru.practicum.crm.sla.api.controller;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.common.error.ApiException;
import ru.practicum.crm.common.error.ErrorCode;
import ru.practicum.crm.common.error.ValidationError;
import ru.practicum.crm.common.pagination.PageRequests;
import ru.practicum.crm.common.pagination.PageResponse;
import ru.practicum.crm.sla.api.dto.HolidayRequest;
import ru.practicum.crm.sla.api.dto.HolidayResponse;
import ru.practicum.crm.sla.domain.Holiday;
import ru.practicum.crm.sla.service.HolidayService;
import ru.practicum.crm.tenant.api.TenantContext;

/**
 * Справочник нерабочих дней для администратора (T-057): просмотр, добавление нерабочей или
 * рабочей даты, удаление. Право {@code SLA_POLICY_MANAGE} проверяет сервис, без него — 403.
 *
 * <p>Список — страница по общему контракту, по порядку дат; {@code sort} не принимается. Записи
 * не изменяются: чтобы исправить дату, её удаляют и добавляют заново.
 */
@RestController
@RequestMapping("/admin/calendar/holidays")
public class HolidayController {

    private static final String DAY_OFF_HOURS = "у нерабочего дня часов нет";

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Сервис — Spring-бин, внедряется как зависимость. SpotBugs считает "
                    + "его изменяемым из-за репозитория с методом delete")
    private final HolidayService service;

    @SuppressFBWarnings(
            value = "EI_EXPOSE_REP2",
            justification = "Контекст арендатора — Spring-бин, внедряется как зависимость. "
                    + "SpotBugs считает его изменяемым из-за метода setTenantId")
    private final TenantContext tenantContext;

    public HolidayController(HolidayService service, TenantContext tenantContext) {
        this.service = service;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public PageResponse<HolidayResponse> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            HttpServletRequest request) {
        String[] sort = request.getParameterValues("sort");
        return PageResponse.of(service.holidays(tenantContext.getCurrentTenantId(),
                        PageRequests.toPageable(page, size, sort == null ? null : List.of(sort),
                                Set.of()))
                .map(HolidayController::toResponse));
    }

    /**
     * Нерабочая дата или, с {@code working: true}, рабочая дата с часами. Повтор даты — 409.
     * Часы у нерабочей даты — ошибка в запросе, а не молча отброшенные поля.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public HolidayResponse create(@RequestBody HolidayRequest body) {
        UUID tenantId = tenantContext.getCurrentTenantId();
        if (body.working()) {
            return toResponse(service.createWorkingDay(tenantId, body.date(), body.description(),
                    body.start(), body.end()));
        }
        rejectHoursOfDayOff(body);
        return toResponse(service.createDayOff(tenantId, body.date(), body.description()));
    }

    @DeleteMapping("/{holidayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID holidayId) {
        service.delete(tenantContext.getCurrentTenantId(), holidayId);
    }

    private static void rejectHoursOfDayOff(HolidayRequest body) {
        List<ValidationError> errors = new ArrayList<>();
        if (body.start() != null) {
            errors.add(ValidationError.ofField("start", DAY_OFF_HOURS));
        }
        if (body.end() != null) {
            errors.add(ValidationError.ofField("end", DAY_OFF_HOURS));
        }
        if (!errors.isEmpty()) {
            throw new ApiException(ErrorCode.VALIDATION_FAILED, null, errors);
        }
    }

    private static HolidayResponse toResponse(Holiday holiday) {
        return new HolidayResponse(holiday.getId(), holiday.getDate(), holiday.getDescription(),
                holiday.isWorking(), holiday.getStartTime(), holiday.getEndTime(),
                holiday.getCreatedAt());
    }
}
