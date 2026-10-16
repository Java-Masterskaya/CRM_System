package ru.practicum.crm.analytics.api.controller;

import java.time.LocalDate;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.practicum.crm.analytics.api.dto.InboundByDayReportResponse;
import ru.practicum.crm.analytics.service.InboundRequestReportService;

@RestController
@RequestMapping("/analytics/request")
@RequiredArgsConstructor
public class InboundRequestReportController {

    private final InboundRequestReportService inboundRequestService;

    @GetMapping("/inbound")
    public InboundByDayReportResponse reportByPeriod(
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate from,
            @RequestParam
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
            LocalDate to,
            @RequestParam(required = false, defaultValue = "none")
            String groupBy,
            @RequestParam(required = false)
            UUID typeId,
            @RequestParam(required = false)
            String priority
    ) {
        return inboundRequestService.report(from, to, groupBy, typeId, priority);
    }
}
